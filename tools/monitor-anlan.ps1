param(
    [Parameter(Mandatory=$true)][string]$Device,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [int]$Minutes = 30,
    [int]$IntervalSeconds = 60
)
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
if ($Device -notmatch '^[A-Za-z0-9.:_-]+$' -or $Minutes -lt 1 -or $IntervalSeconds -lt 10) { throw '监控参数无效' }
$adb = (Get-Command adb.exe -ErrorAction Stop).Source
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
function Save-Json($Name, $Value) {
    [IO.File]::WriteAllText((Join-Path $OutputDirectory $Name), ($Value | ConvertTo-Json -Depth 10), $utf8)
}
function Read-Device([string]$Command, [switch]$Direct) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $adb
    $tail = if ($Direct) { $Command } else { 'shell "' + $Command.Replace('"','\"') + '"' }
    $info.Arguments = '-s ' + $Device + ' ' + $tail
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(18000)) { $process.Kill(); throw 'ADB采样超时' }
    $result = $stdout.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw ('ADB采样失败: ' + $stderr.GetAwaiter().GetResult().Trim()) }
    $process.Dispose()
    return $result
}
function Section([string]$Source, [string]$Name) {
    $match = [regex]::Match($Source, '(?ms)^__' + $Name + '__\r?\n(.*?)(?=^__[A-Z]+__|\z)')
    return $match.Groups[1].Value.Trim()
}
function Number([string]$Source,[string]$Pattern,[double]$Scale=1) {
    $match = [regex]::Match($Source,$Pattern)
    if ($match.Success) { return [double]::Parse($match.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture) / $Scale }
    return $null
}
$start = [DateTimeOffset]::Now
$watch = [Diagnostics.Stopwatch]::StartNew()
$deadline = $start.AddMinutes($Minutes)
$deviceStart = (Read-Device 'date +%m-%d\ %H:%M:%S.000').Trim()
$metadata = [ordered]@{
    device=$Device; package='com.anlan.guardian'; start=$start.ToString('o'); deadline=$deadline.ToString('o')
    intervalSeconds=$IntervalSeconds; durationMinutes=$Minutes; state='RUNNING'; samplerPid=$PID
    deviceInfo=(Read-Device 'getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; getconf CLK_TCK; getconf _NPROCESSORS_ONLN')
    installedVersion=(Read-Device "dumpsys package com.anlan.guardian | grep -E 'versionCode=|versionName=|lastUpdateTime='")
    limitations=@('只读观察，不安装APK、不重启服务、不清除日志、不注入交互。','USB供电情况下不将电量变化当作纯电池耗电。','每分钟快照不能排除采样间瞬时内存峰值，退出历史和异常摘要用于补充。','不保存录音、用户转写、模型正文或凭据；异常只保存签名元数据。')
}
$deviceParts = $metadata.deviceInfo.Trim() -split '\s+'
$clockTicks = [double]$deviceParts[$deviceParts.Length-2]
$cores = [double]$deviceParts[$deviceParts.Length-1]
Save-Json 'run.json' $metadata
$previous = $null; $sampleCount=0; $failures=0
$command = @'
echo __UPTIME__; cat /proc/uptime
echo __STAT__; p=$(pidof com.anlan.guardian | awk '{print $1}'); if [ -n "$p" ]; then run-as com.anlan.guardian cat /proc/$p/stat; fi
echo __MEMORY__; cat /proc/meminfo | head -n 6
echo __BATTERY__; dumpsys battery
echo __CPU__; cat /proc/stat | head -n 1
echo __PRESSURE__; cat /proc/pressure/memory 2>/dev/null || true
'@
try {
    $slots = [int][Math]::Floor($Minutes*60/$IntervalSeconds)
    for ($index=0; $index -le $slots; $index++) {
        $due = $index*$IntervalSeconds
        while ($watch.Elapsed.TotalSeconds -lt $due) { Start-Sleep -Milliseconds ([int][Math]::Min(1000,($due-$watch.Elapsed.TotalSeconds)*1000+1)) }
        $sample = [ordered]@{index=$index;at=[DateTimeOffset]::Now.ToString('o');elapsedSeconds=[Math]::Round($watch.Elapsed.TotalSeconds,3);errors=@()}
        try {
            $raw=Read-Device $command
            $uptimeText=Section $raw 'UPTIME'; $stat=Section $raw 'STAT'; $battery=Section $raw 'BATTERY'
            $sample.deviceUptimeSeconds=Number $uptimeText '^([\d.]+)'
            $sample.pid=Number $stat '^(\d+)'
            if ($sample.pid) {
                $statFields=($stat.Substring($stat.LastIndexOf(')')+2)) -split '\s+'
                $sample.cpuTicks=[double]$statFields[11]+[double]$statFields[12]
                $sample.threads=[int]$statFields[17]; $sample.processStartTicks=[double]$statFields[19]
                if ($previous -and $previous.pid -eq $sample.pid -and $previous.processStartTicks -eq $sample.processStartTicks) {
                    $elapsed=$sample.deviceUptimeSeconds-$previous.deviceUptimeSeconds
                    if ($elapsed -gt 0) {
                        $sample.cpuOneCorePercent=[Math]::Round(100*($sample.cpuTicks-$previous.cpuTicks)/$clockTicks/$elapsed,3)
                        $sample.cpuWholeDevicePercent=[Math]::Round($sample.cpuOneCorePercent/$cores,3)
                    }
                }
            }
            $sample.memAvailableKiB=Number (Section $raw 'MEMORY') 'MemAvailable:\s+(\d+)'
            $sample.memTotalKiB=Number (Section $raw 'MEMORY') 'MemTotal:\s+(\d+)'
            $sample.batteryTempC=Number $battery '(?m)^\s*temperature:\s*(\d+)' 10
            $sample.batteryPercent=Number $battery '(?m)^\s*level:\s*(\d+)'
            $sample.chargeCounterUah=Number $battery '(?m)^\s*Charge counter:\s*(-?\d+)'
            $sample.batteryStatus=Number $battery '(?m)^\s*status:\s*(\d+)'
            $sample.usbPowered=$battery -match 'USB powered:\s*true'
            $sample.memoryPressure=Section $raw 'PRESSURE'
            $sample.systemCpuCounters=Section $raw 'CPU'
            $memory=Read-Device 'dumpsys meminfo com.anlan.guardian'
            [IO.File]::WriteAllText((Join-Path $OutputDirectory ('memory-{0:D2}.txt' -f $index)),$memory,$utf8)
            $sample.pssKiB=Number $memory 'TOTAL PSS:\s+(\d+)'
            $sample.rssKiB=Number $memory 'TOTAL RSS:\s+(\d+)'
            $sample.swapPssKiB=Number $memory 'TOTAL SWAP PSS:\s+(\d+)'
            $sample.nativeAllocatedKiB=Number $memory '(?m)^\s*Native Heap\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+(\d+)'
            $sample.javaAllocatedKiB=Number $memory '(?m)^\s*Dalvik Heap\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+\d+\s+(\d+)'
            $thermal=Read-Device 'dumpsys thermalservice'
            [IO.File]::WriteAllText((Join-Path $OutputDirectory ('thermal-{0:D2}.txt' -f $index)),$thermal,$utf8)
            $sample.thermalStatus=Number $thermal '(?m)^Thermal Status:\s*(\d+)'
            $current=($thermal -split 'Current temperatures from HAL:',2)[-1] -split 'Current cooling devices from HAL:',2 | Select-Object -First 1
            $sample.temperatures=@([regex]::Matches($current,'Temperature\{mValue=([\d.]+), mType=(\d+), mName=([^,}]+), mStatus=(\d+)\}') | ForEach-Object { [ordered]@{celsius=[double]::Parse($_.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture);type=[int]$_.Groups[2].Value;name=$_.Groups[3].Value;status=[int]$_.Groups[4].Value} })
            $sample.foreground=(Read-Device "dumpsys activity activities | grep topResumedActivity").Trim()
            $logs=Read-Device ('logcat -d -b main -b system -b crash -b events -T "'+$deviceStart+'" -t 6000 -v threadtime AndroidRuntime:E libc:F DEBUG:F Anlan:E AnlanWake:I AnlanCapture:I am_crash:I am_anr:I am_kill:I am_proc_start:I am_proc_died:I lmkd:I *:S')
            $sample.diagnosticSignatures=@($logs -split '\r?\n' | Where-Object {$_ -match 'OutOfMemory|FATAL EXCEPTION|ANR|Fatal signal|allocation fail|com\.anlan\.guardian|Anlan\s*:|AnlanWake\s*:|AnlanCapture\s*:'} | ForEach-Object {
                $line=$_; $prefix=[regex]::Match($line,'^\d\d-\d\d\s+[\d:.]+\s+\d+\s+\d+\s+[A-Z]\s+[^:]+').Value
                $kind=if($line -match 'OutOfMemory'){ 'OOM' }elseif($line -match 'ANR|am_anr'){'ANR'}elseif($line -match 'FATAL EXCEPTION|am_crash|Fatal signal'){'CRASH'}elseif($line -match 'am_kill|am_proc_died'){'PROCESS_EXIT'}elseif($line -match 'AnlanWake'){'WAKE'}elseif($line -match 'AnlanCapture'){'CAPTURE'}else{'APP_ERROR_OR_PROCESS_EVENT'}
                $exception=[regex]::Match($line,'[A-Za-z0-9_.$]+(?:Exception|Error)\b').Value
                [ordered]@{prefix=$prefix;kind=$kind;exception=$exception;mentionsApp=($line -match 'com\.anlan\.guardian');matchesPid=($sample.pid -and $line -match ('\b'+[int]$sample.pid+'\b'))}
            } | Select-Object -Last 500)
            $exits=Read-Device 'dumpsys activity exit-info com.anlan.guardian'
            $sample.exitHistory=@($exits -split '\r?\n' | Where-Object {$_ -match 'ApplicationExitInfo #|timestamp=|importance='} | ForEach-Object { ($_ -split ' description=| trace=')[0].Trim() })
            $sampleCount++; $previous=$sample
        } catch { $sample.errors+= $_.Exception.Message; $failures++ }
        [IO.File]::AppendAllText((Join-Path $OutputDirectory 'samples.jsonl'),(($sample | ConvertTo-Json -Depth 10 -Compress)+[Environment]::NewLine),$utf8)
        Save-Json 'latest.json' $sample
        Save-Json 'progress.json' ([ordered]@{state='RUNNING';completedSlots=$index+1;expectedSlots=$slots+1;successfulSamples=$sampleCount;failedSamples=$failures;lastSampleAt=$sample.at;deadline=$deadline.ToString('o')})
    }
    $metadata.state='COMPLETE'
} catch { $metadata.state='FAILED'; $metadata.error=$_.Exception.Message }
finally {
    $metadata.end=[DateTimeOffset]::Now.ToString('o'); $metadata.successfulSamples=$sampleCount; $metadata.failedSamples=$failures
    Save-Json 'run.json' $metadata
    Save-Json 'progress.json' $metadata
}
