param(
    [ValidateRange(1, 3600)] [int]$Seconds = 30,
    [string]$Serial = '',
    [ValidateRange(640, 3840)] [int]$MaxSize = 1920,
    [string]$AdbPath = '',
    [string]$ScrcpyPath = ''
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Resolve-AndroidTools.ps1')
if (!$AdbPath) { $AdbPath = Join-Path (Resolve-AndroidSdk) 'platform-tools/adb.exe' }
if (!$ScrcpyPath) {
    $command = Get-Command scrcpy -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$command) { throw 'Install scrcpy and add it to PATH, or pass -ScrcpyPath.' }
    $ScrcpyPath = $command.Source
}
$scrcpy = $ScrcpyPath
$adb = $AdbPath
foreach ($file in @($scrcpy, $adb)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing tool: $file" }
}
if (!$Serial) {
    $connected = @(& $adb devices | ForEach-Object {
        if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
    })
    if ($LASTEXITCODE -ne 0 -or $connected.Count -ne 1) {
        throw 'Connect exactly one authorized phone, or specify -Serial.'
    }
    $Serial = $connected[0]
}
$folder = Join-Path $workspace ('recordings/scrcpy-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $folder | Out-Null
$video = Join-Path $folder 'screen.mp4'
$previousAdb = $env:ADB
try {
    $env:ADB = $adb
    # Read-only mirroring: operate on the phone. No WRITE_SETTINGS/INJECT_EVENTS required.
    # Physical touch indicators can be enabled manually in the phone's Developer options.
    & $scrcpy "--serial=$Serial" --no-audio --no-control "--max-size=$MaxSize" --max-fps=30 `
        "--time-limit=$Seconds" '--window-title=BA Grid Master screen recording' "--record=$video" `
        2>&1 | Tee-Object -FilePath (Join-Path $folder 'scrcpy.log')
    if ($LASTEXITCODE -ne 0) { throw "scrcpy failed. See $folder" }
    Write-Output "Saved: $video"
} finally {
    $env:ADB = $previousAdb
}
