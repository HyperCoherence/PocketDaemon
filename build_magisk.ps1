# Build PocketDaemon Magisk module
# Run after: flutter build apk --release

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$apkSource = "build\app\outputs\flutter-apk\app-release.apk"
$magiskDir = "magisk"
$privAppDir = "$magiskDir\system\priv-app\PocketDaemon"
$outputZip = "PocketDaemon-magisk.zip"

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
    $propPath = "$magiskDir\module.prop"
    $prop = Get-Content $propPath -Raw
    $prop = $prop -replace '(?m)^version=.*$', "version=$ver"
    $prop = $prop -replace '(?m)^versionCode=.*$', "versionCode=$code"
    Set-Content $propPath $prop -NoNewline
    Write-Host "module.prop version=$ver versionCode=$code"
}

New-Item -ItemType Directory -Force -Path $privAppDir | Out-Null
Copy-Item $apkSource "$privAppDir\PocketDaemon.apk" -Force
Write-Host "Copied APK to $privAppDir\PocketDaemon.apk"

if (Test-Path $outputZip) { Remove-Item $outputZip }

$zipPath = (Resolve-Path -Path ".").Path + "\$outputZip"
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')

$basePath = (Resolve-Path $magiskDir).Path
Get-ChildItem -Path $basePath -Recurse -File | ForEach-Object {
    $relativePath = $_.FullName.Substring($basePath.Length + 1) -replace '\\', '/'
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $_.FullName, $relativePath, 'Optimal') | Out-Null
}

$zip.Dispose()
Write-Host "Created $outputZip (forward-slash paths)"
Write-Host ""
Write-Host "Flash via Magisk app > Modules > Install from storage > $outputZip"
Write-Host "Then reboot."
