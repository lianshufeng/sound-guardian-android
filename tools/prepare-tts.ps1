$ErrorActionPreference = 'Stop'
# Reuse the pinned runtime installer and its verified-download helpers.
. (Join-Path $PSScriptRoot 'prepare-wakeword.ps1')
$ttsDirectory = Join-Path $repository 'core/src/main/assets/tts'
$ttsManifest = Get-Content -LiteralPath (Join-Path $ttsDirectory 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$ttsModelDirectory = Join-Path $ttsDirectory 'model'
$ttsCacheDirectory = Join-Path $repository 'evaluation/tts-setup'
New-Item -ItemType Directory -Force -Path $ttsModelDirectory, $ttsCacheDirectory | Out-Null
$missingTts = @($ttsManifest.files.PSObject.Properties | Where-Object { -not (Test-Path -LiteralPath (Join-Path $ttsModelDirectory $_.Name)) })
$modelChanged = Test-Path -LiteralPath (Join-Path $ttsModelDirectory 'model.onnx')
if ($modelChanged) { $missingTts = @($ttsManifest.files.PSObject.Properties) }
if ($missingTts.Count -gt 0) {
    $ttsArchive = Join-Path $ttsCacheDirectory ($ttsManifest.model + '.tar.bz2')
    Get-VerifiedFile $ttsManifest.source $ttsArchive $ttsManifest.archive_sha256
    # Validate upstream archive paths before extraction.
    $members = & tar -tf $ttsArchive
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read TTS archive' }
    foreach ($member in $members) {
        if (-not $member.StartsWith($ttsManifest.model + '/') -or $member -match '(^|/)\.\.(/|$)' -or $member.Contains('\')) {
            throw "Unexpected TTS archive path: $member"
        }
    }
    & tar -xf $ttsArchive -C $ttsCacheDirectory
    if ($LASTEXITCODE -ne 0) { throw 'TTS extraction failed' }
    $vocoder = Join-Path $ttsCacheDirectory 'vocos-22khz-univ.onnx'
    Get-VerifiedFile $ttsManifest.vocoder_source $vocoder $ttsManifest.vocoder_sha256
    # Validate all new files before replacing a legacy VITS asset directory.
    foreach ($entry in $ttsManifest.files.PSObject.Properties) {
        $source = if ($entry.Name -eq 'vocos-22khz-univ.onnx') { $vocoder } else { Join-Path $ttsCacheDirectory ($ttsManifest.model + '/' + $entry.Name) }
        Assert-Hash $source $entry.Value
    }
    if ($modelChanged) {
        $resolved = [IO.Path]::GetFullPath($ttsModelDirectory)
        $expected = [IO.Path]::GetFullPath((Join-Path $repository 'core/src/main/assets/tts/model'))
        if ($resolved -ne $expected) { throw 'Unexpected TTS migration path' }
        $backup = Join-Path $ttsCacheDirectory ('vits-backup-' + [Guid]::NewGuid().ToString('N'))
        if (-not [IO.Path]::GetFullPath($backup).StartsWith([IO.Path]::GetFullPath($ttsCacheDirectory) + [IO.Path]::DirectorySeparatorChar)) { throw 'Unexpected TTS backup path' }
        Move-Item -LiteralPath $resolved -Destination $backup
        New-Item -ItemType Directory -Path $resolved | Out-Null
    }
    foreach ($entry in $ttsManifest.files.PSObject.Properties) {
        $destination = Join-Path $ttsModelDirectory $entry.Name
        if (Test-Path -LiteralPath $destination) { Assert-Hash $destination $entry.Value; continue }
        $source = if ($entry.Name -eq 'vocos-22khz-univ.onnx') { $vocoder } else { Join-Path $ttsCacheDirectory ($ttsManifest.model + '/' + $entry.Name) }
        Assert-Hash $source $entry.Value
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
    }
}
foreach ($entry in $ttsManifest.files.PSObject.Properties) { Assert-Hash (Join-Path $ttsModelDirectory $entry.Name) $entry.Value }
Write-Output 'Offline Chinese Matcha + Vocos TTS ready. SHA-256 verified. See tts/config.json for voice and speed. Baker dataset is non-commercial only.'
