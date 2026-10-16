$ErrorActionPreference = 'Stop'
$assetDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../core/src/main/assets'))
$modelPath = Join-Path $assetDirectory 'yamnet.tflite'
$expectedHash = '4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf'
$url = 'https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite'

if (Test-Path -LiteralPath $modelPath) {
    if ((Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
        throw 'Existing model hash mismatch. Preserve and inspect it; this script will not overwrite it.'
    }
    Write-Output 'Model exists. SHA-256 verified.'
    exit 0
}

$temporaryPath = Join-Path $assetDirectory ('yamnet-' + [Guid]::NewGuid().ToString('N') + '.download')
try {
    Invoke-WebRequest -Uri $url -OutFile $temporaryPath -UseBasicParsing
    if ((Get-FileHash -LiteralPath $temporaryPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
        throw 'Downloaded model hash mismatch. Model was not installed.'
    }
    Move-Item -LiteralPath $temporaryPath -Destination $modelPath
    Write-Output 'Official model ready. SHA-256 verified.'
} finally {
    if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath }
}
