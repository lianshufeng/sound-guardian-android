# Run with Windows PowerShell 5.1 and the Microsoft Huihui Desktop voice.
param([switch]$OnlySpeedVariants)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputDirectory = Join-Path $repository 'core/src/androidTest/assets/wakeword-test'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $synth.SelectVoice('Microsoft Huihui Desktop')
    $format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    # Unicode escapes keep this script readable by Windows PowerShell 5.1 without a BOM.
    $phrases = @{
        'positive' = (-join @([char]0x5C0F, [char]0x5B89))
        'negative' = (-join @([char]0x4ECA, [char]0x5929, [char]0x5929, [char]0x6C14, [char]0x4E0D, [char]0x9519))
        'similar' = (-join @([char]0x5C0F, [char]0x738B, [char]0xFF0C, [char]0x5C0F, [char]0x5F20))
        'positive-fast' = (-join @([char]0x5C0F, [char]0x5B89))
        'positive-faster' = (-join @([char]0x5C0F, [char]0x5B89))
        'negative-fast' = (-join @([char]0x4ECA, [char]0x5929, [char]0x5929, [char]0x6C14, [char]0x4E0D, [char]0x9519))
        'similar-fast' = (-join @([char]0x5C0F, [char]0x738B, [char]0xFF0C, [char]0x5C0F, [char]0x5F20))
    }
    $rates = @{ 'positive-fast' = 2; 'positive-faster' = 4; 'negative-fast' = 2; 'similar-fast' = 2 }
    foreach ($entry in $phrases.GetEnumerator()) {
        if ($OnlySpeedVariants -and -not $rates.ContainsKey($entry.Key)) { continue }
        $synth.Rate = 0
        if ($rates.ContainsKey($entry.Key)) { $synth.Rate = $rates[$entry.Key] }
        $wave = Join-Path $outputDirectory ($entry.Key + '.wav')
        $synth.SetOutputToWaveFile($wave, $format)
        $synth.Speak($entry.Value)
        $synth.SetOutputToNull()
        # Extract RIFF data chunk and add one second of silence for KWS trailing blanks.
        $reader = [IO.BinaryReader]::new([IO.File]::OpenRead($wave))
        try {
            $reader.BaseStream.Position = 12
            while ($reader.BaseStream.Position -lt $reader.BaseStream.Length) {
                $id = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
                $length = $reader.ReadUInt32()
                if ($id -eq 'data') {
                    $samples = $reader.ReadBytes($length)
                    $pcm = New-Object byte[] ($samples.Length + 64000)
                    [Array]::Copy($samples, 0, $pcm, 32000, $samples.Length)
                    [IO.File]::WriteAllBytes((Join-Path $outputDirectory ($entry.Key + '.pcm')), $pcm)
                    break
                }
                $reader.BaseStream.Position += $length + ($length % 2)
            }
        } finally { $reader.Dispose() }
    }
} finally { $synth.Dispose() }
Write-Output 'Synthetic 16 kHz mono PCM fixtures ready; no human recording is used.'
