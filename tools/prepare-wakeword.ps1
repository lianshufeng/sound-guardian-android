$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$assetDirectory = Join-Path $repository 'core/src/main/assets/wakeword'
$manifest = Get-Content -LiteralPath (Join-Path $assetDirectory 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$cacheDirectory = Join-Path $repository 'evaluation/wakeword-setup'
$libraryDirectory = Join-Path $repository 'libs'
New-Item -ItemType Directory -Force -Path $cacheDirectory, $libraryDirectory | Out-Null

function Assert-Hash([string]$Path, [string]$Expected) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) {
        throw "SHA-256 mismatch. Preserve and inspect existing file: $Path"
    }
}

function Get-VerifiedFile([string]$Url, [string]$Path, [string]$Expected) {
    if (Test-Path -LiteralPath $Path) { Assert-Hash $Path $Expected; return }
    $temporary = $Path + '.download'
    try {
        Invoke-WebRequest -Uri $Url -OutFile $temporary -UseBasicParsing
        Assert-Hash $temporary $Expected
        Move-Item -LiteralPath $temporary -Destination $Path
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}

$version = $manifest.runtime_version
$runtime = Join-Path $libraryDirectory "sherpa-onnx-$version.aar"
Get-VerifiedFile "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar" $runtime $manifest.runtime_sha256
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($runtime)
try {
    # compileOnly JAR is extracted from the verified AAR; apps opt in to the native runtime.
    $classes = Join-Path $libraryDirectory "sherpa-onnx-$version-classes.jar"
    [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $classes, $true)
} finally { $archive.Dispose() }

$missing = @($manifest.files.PSObject.Properties | Where-Object { -not (Test-Path -LiteralPath (Join-Path $assetDirectory $_.Name)) })
if ($missing.Count -gt 0) {
    $modelArchive = Join-Path $cacheDirectory 'kws-model.tar.bz2'
    Get-VerifiedFile $manifest.source $modelArchive $manifest.archive_sha256
    # Extract only pinned members from the verified official archive.
    $members = @($manifest.files.PSObject.Properties | ForEach-Object { $manifest.model + '/' + $_.Name })
    & tar -xf $modelArchive -C $cacheDirectory @members
    if ($LASTEXITCODE -ne 0) { throw 'Model extraction failed' }
    foreach ($entry in $manifest.files.PSObject.Properties) {
        $destination = Join-Path $assetDirectory $entry.Name
        if (Test-Path -LiteralPath $destination) { Assert-Hash $destination $entry.Value; continue }
        $source = Join-Path $cacheDirectory ($manifest.model + '/' + $entry.Name)
        Assert-Hash $source $entry.Value
        Copy-Item -LiteralPath $source -Destination $destination
    }
}
foreach ($entry in $manifest.files.PSObject.Properties) { Assert-Hash (Join-Path $assetDirectory $entry.Name) $entry.Value }
Write-Output 'Offline wake-word runtime and model ready. SHA-256 verified.'
