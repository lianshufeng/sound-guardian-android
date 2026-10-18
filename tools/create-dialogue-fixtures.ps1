# Windows PowerShell 5.1 / Microsoft Huihui; text is loaded explicitly as UTF-8.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputDirectory = Join-Path $repository 'anlan/src/androidTest/assets/dialogue-test'
$phrases = [IO.File]::ReadAllText((Join-Path $outputDirectory 'phrases.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$utf8 = New-Object Text.UTF8Encoding($false)
try {
    $synth.SelectVoice('Microsoft Huihui Desktop')
    $format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    $manifest = @()
    foreach ($phrase in $phrases) {
        foreach ($variant in @(@{name='normal';rate=0}, @{name='fast';rate=2}, @{name='slow';rate=-2})) {
            $synth.Rate = $variant.rate
            $name = $phrase.id + '-' + $variant.name
            $wave = Join-Path $outputDirectory ($name + '.wav')
            $synth.SetOutputToWaveFile($wave, $format)
            $synth.Speak($phrase.text)
            $synth.SetOutputToNull()
            $reader = [IO.BinaryReader]::new([IO.File]::OpenRead($wave))
            try {
                $reader.BaseStream.Position = 12
                while ($reader.BaseStream.Position -lt $reader.BaseStream.Length) {
                    $id = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
                    $length = $reader.ReadUInt32()
                    if ($id -eq 'data') {
                        $samples = $reader.ReadBytes($length)
                        $pcm = New-Object byte[] ($samples.Length + 64000)
                        [Array]::Copy($samples,0,$pcm,32000,$samples.Length)
                        $destination = Join-Path $outputDirectory ($name + '.pcm')
                        [IO.File]::WriteAllBytes($destination,$pcm)
                        $manifest += @{name=$name; text=$phrase.text; source='Microsoft Huihui Desktop synthetic'; rate=$variant.rate; sample_rate=16000; format='s16le-mono'; duration_ms=($pcm.Length/32); sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()}
                        break
                    }
                    $reader.BaseStream.Position += $length + ($length % 2)
                }
            } finally { $reader.Close() }
            Remove-Item -LiteralPath $wave
        }
    }
    [IO.File]::WriteAllText((Join-Path $outputDirectory 'manifest.json'), (ConvertTo-Json -InputObject $manifest -Depth 4), $utf8)
    Write-Output ('Generated PCM samples: ' + $manifest.Count)
} finally { $synth.Dispose() }
