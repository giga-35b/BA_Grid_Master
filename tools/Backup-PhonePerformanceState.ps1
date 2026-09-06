param(
    [Parameter(Mandatory = $true)][string]$AdbPath,
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$BackupDirectory
)
$ErrorActionPreference = 'Stop'
$package = 'com.bagridmaster.app'
if (Test-Path -LiteralPath $BackupDirectory) { throw 'Backup directory already exists; do not overwrite it.' }
New-Item -ItemType Directory -Path $BackupDirectory | Out-Null
& $AdbPath -s $Serial shell am force-stop $package
if ($LASTEXITCODE -ne 0) { throw 'Could not stop application before backup.' }

# These are the exact files inspected for this test. Do not copy other application data.
$paths = @(
    'files/datastore/app_settings.preferences_pb',
    'files/datastore/board_calibration.preferences_pb',
    'code_cache/perf_agent_backup_20260831_noagent/b0fe613d-agent.so',
    'code_cache/.overlay/id',
    'code_cache/.overlay/base.apk/classes3.dex',
    'code_cache/.overlay/base.apk/classes5.dex',
    'code_cache/.overlay/base.apk/classes6.dex',
    'code_cache/.overlay/base.apk/classes8.dex',
    'code_cache/.overlay/base.apk/classes10.dex'
)
$manifest = foreach ($remote in $paths) {
    $destination = Join-Path $BackupDirectory $remote
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    $remoteHashLine = & $AdbPath -s $Serial shell run-as $package sha256sum $remote
    if ($LASTEXITCODE -ne 0) { throw "Could not hash $remote" }
    $remoteHash = ($remoteHashLine -split '\s+')[0]
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $AdbPath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @('-s', $Serial, 'exec-out', 'run-as', $package, 'cat', $remote)) {
        $start.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    $file = [System.IO.File]::Open($destination, [System.IO.FileMode]::CreateNew)
    try {
        [void]$process.Start()
        $stderr = $process.StandardError.ReadToEndAsync()
        # Binary output must not pass through PowerShell text encoding.
        $process.StandardOutput.BaseStream.CopyTo($file)
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw $stderr.GetAwaiter().GetResult() }
    } finally { $file.Dispose(); $process.Dispose() }
    $localHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash
    if ($localHash -ne $remoteHash) { throw "Backup hash mismatch: $remote" }
    [pscustomobject]@{Path=$remote; Bytes=(Get-Item -LiteralPath $destination).Length; SHA256=$localHash}
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $BackupDirectory 'verified-files.json') -Encoding utf8
$manifest | Format-Table -AutoSize
Write-Output "Verified backup: $BackupDirectory"
