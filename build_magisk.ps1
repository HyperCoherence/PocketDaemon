# Build PocketDaemon Magisk module
# Run after: flutter build apk --release

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$apkSource = "build\app\outputs\flutter-apk\app-release.apk"
$magiskDir = "magisk"
$privAppDir = "$magiskDir\system\priv-app\PocketDaemon"
$releaseDir = "releases"
$artifactVersion = "dev"

if (-not (Test-Path $apkSource)) {
    Write-Host "APK not found at $apkSource"
    Write-Host "Run 'flutter build apk --release' first."
    exit 1
}

# Sync version from pubspec.yaml into module.prop
$pubspec = Get-Content "pubspec.yaml" -Raw
if ($pubspec -match 'version:\s*(\d+\.\d+\.\d+)\+(\d+)') {
    $ver = $Matches[1]
    $code = $Matches[2]
    $artifactVersion = "$ver-$code"
    $propPath = "$magiskDir\module.prop"
    $prop = Get-Content $propPath -Raw
    $prop = $prop -replace '(?m)^version=.*$', "version=$ver"
    $prop = $prop -replace '(?m)^versionCode=.*$', "versionCode=$code"
    Set-Content $propPath $prop -NoNewline
    Write-Host "module.prop version=$ver versionCode=$code"
}

New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
New-Item -ItemType Directory -Force -Path $privAppDir | Out-Null

$releaseApk = Join-Path $releaseDir "PocketDaemon-$artifactVersion.apk"
$outputZip = Join-Path $releaseDir "PocketDaemon-$artifactVersion-magisk.zip"
$checksumsPath = Join-Path $releaseDir "SHA256SUMS.txt"

Copy-Item $apkSource $releaseApk -Force
Write-Host "Copied APK to $releaseApk"

Copy-Item $apkSource "$privAppDir\PocketDaemon.apk" -Force
Write-Host "Copied APK to $privAppDir\PocketDaemon.apk"

if (Test-Path $outputZip) { Remove-Item $outputZip }

$zipPath = (Resolve-Path -Path $releaseDir).Path + "\PocketDaemon-$artifactVersion-magisk.zip"
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')

$basePath = (Resolve-Path $magiskDir).Path
Get-ChildItem -Path $basePath -Recurse -File | ForEach-Object {
    $relativePath = $_.FullName.Substring($basePath.Length + 1) -replace '\\', '/'
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $_.FullName, $relativePath, 'Optimal') | Out-Null
}

$zip.Dispose()

$checksumLines = foreach ($artifact in @($releaseApk, $outputZip)) {
    $hash = Get-FileHash -Algorithm SHA256 $artifact
    "$(($hash.Hash).ToLowerInvariant())  $(Split-Path -Leaf $artifact)"
}
Set-Content -Path $checksumsPath -Value $checksumLines

Write-Host "Created $outputZip (forward-slash paths)"
Write-Host "Wrote $checksumsPath"
Write-Host ""
Write-Host "Release artifacts are in $releaseDir."
Write-Host "Flash via Magisk app > Modules > Install from storage > $(Split-Path -Leaf $outputZip)"
Write-Host "Then reboot."
