$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'prepare-wakeword.ps1')
$understandingDirectory = Join-Path $repository 'core/src/main/assets/understanding'
$understandingManifest = Get-Content -LiteralPath (Join-Path $understandingDirectory 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($entry in $understandingManifest.files.PSObject.Properties) {
    $remoteName = if ($entry.Name -eq 'SENSEVOICE-LICENSE') { 'LICENSE' } else { $entry.Name }
    $url = if ($entry.Name -eq 'silero_vad.onnx') { $understandingManifest.vad_url } else { $understandingManifest.base_url + $remoteName }
    $source = $understandingManifest.sources.PSObject.Properties[$entry.Name]
    if ($null -ne $source) { $url = $source.Value }
    Get-VerifiedFile $url (Join-Path $understandingDirectory $entry.Name) $entry.Value
}
Write-Output 'SenseVoice INT8 and Silero VAD ready. SHA-256 verified.'
