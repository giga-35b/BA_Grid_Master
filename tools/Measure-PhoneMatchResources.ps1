param(
    [Parameter(Mandatory = $true)][string]$AdbPath,
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [ValidateRange(15, 240)][int]$DurationSeconds = 180
)
$ErrorActionPreference = 'Stop'
$package = 'com.bagridmaster.app'
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Refusing to overwrite an existing diagnostic directory.' }
$appProcessId = (& $AdbPath -s $Serial shell pidof $package).Trim()
if ($appProcessId -notmatch '^\d+$') { throw 'Expected one running Grid Master process.' }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$recordName = 'match-resource-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.perf.data'
$remoteRecord = "cache/$recordName"
$children = [System.Collections.Generic.List[object]]::new()
function Start-AdbCapture([string[]]$Arguments, [string]$Name, [string]$InputText = '') {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $AdbPath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.RedirectStandardInput = $true
    foreach ($arg in (@('-s', $Serial) + $Arguments)) { $start.ArgumentList.Add($arg) }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    [void]$process.Start()
    $stdout = [System.IO.File]::Open((Join-Path $OutputDirectory "$Name.log"), [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)
    $stderr = [System.IO.File]::Open((Join-Path $OutputDirectory "$Name.err"), [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)
    $copyOut = $process.StandardOutput.BaseStream.CopyToAsync($stdout)
    $copyErr = $process.StandardError.BaseStream.CopyToAsync($stderr)
    if ($InputText) { $process.StandardInput.Write($InputText.Replace("`r`n", "`n")) }
    $process.StandardInput.Close()
    $children.Add([pscustomobject]@{Process=$process; Out=$stdout; Err=$stderr; CopyOut=$copyOut; CopyErr=$copyErr; Name=$Name})
}
try {
    $started = Get-Date
    $metadata = [pscustomobject]@{Package=$package; PID=$appProcessId; HostStart=$started.ToString('o'); DurationSeconds=$DurationSeconds; RemoteRecord=$remoteRecord; SamplingHz=99}
    $metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'metadata.json') -Encoding utf8
    & $AdbPath -s $Serial shell 'date +%s; cat /proc/uptime' | Set-Content -LiteralPath (Join-Path $OutputDirectory 'clock-start.txt')
    & $AdbPath -s $Serial shell dumpsys meminfo $package | Set-Content -LiteralPath (Join-Path $OutputDirectory 'memory-before.txt')
    $sampler = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'sample-match-resources.sh')
    Start-AdbCapture -Arguments @('shell', '-T', 'run-as', $package, 'sh', '-s', '--', $appProcessId, "$DurationSeconds") -Name 'resources' -InputText $sampler
    Start-AdbCapture -Arguments @('logcat', '--pid', $appProcessId, '-v', 'epoch', '-T', '1', 'BAGridAnalysis:I', 'art:I', 'com.bagridmaster.app:I', '*:S') -Name 'analysis'
    # 99 Hz sampling only targets this app; no agents, recompilation, affinity or power changes.
    Start-AdbCapture -Arguments @('shell', 'run-as', $package, 'simpleperf', 'record', '--in-app', '-p', $appProcessId, '-e', 'cpu-clock:u', '-f', '99', '--call-graph', 'dwarf,8192', '--duration', "$DurationSeconds", '--size-limit', '32M', '--user-buffer-size', '8M', '-o', $remoteRecord) -Name 'simpleperf'
    Write-Output "CAPTURE_STARTED pid=$appProcessId seconds=$DurationSeconds directory=$OutputDirectory record=$remoteRecord"
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $DurationSeconds) {
        $phase = [int]$watch.Elapsed.TotalSeconds
        & $AdbPath -s $Serial shell 'date +%s; dumpsys thermalservice; dumpsys battery' | Set-Content -LiteralPath (Join-Path $OutputDirectory "thermal-$phase.txt")
        Start-Sleep -Seconds 5
    }
    & $AdbPath -s $Serial shell dumpsys meminfo $package | Set-Content -LiteralPath (Join-Path $OutputDirectory 'memory-after.txt')
} finally {
    foreach ($child in $children) {
        if ($child.Name -eq 'simpleperf' -and !$child.Process.HasExited) { [void]$child.Process.WaitForExit(30000) }
        if (!$child.Process.HasExited) { $child.Process.Kill() }
        $child.Process.WaitForExit()
        [void]$child.CopyOut.GetAwaiter().GetResult()
        [void]$child.CopyErr.GetAwaiter().GetResult()
        $child.Out.Dispose(); $child.Err.Dispose(); $child.Process.Dispose()
    }
}
Write-Output 'CAPTURE_FINISHED (bounded device collectors stop automatically; perf data retained for report)'
