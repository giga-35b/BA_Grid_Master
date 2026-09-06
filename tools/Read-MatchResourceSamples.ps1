param([Parameter(Mandatory=$true)][string]$Directory)
$ErrorActionPreference = 'Stop'
$samples = [System.Collections.Generic.List[object]]::new()
$current = $null
$thread = $null
foreach ($line in Get-Content -LiteralPath (Join-Path $Directory 'resources.log')) {
    if ($line -match '^SAMPLE (\d+) ([\d.]+)$') {
        $current = [pscustomobject]@{Time=[double]$Matches[2]; Tick=[int]$Matches[1]; Threads=[System.Collections.Generic.List[object]]::new(); Frequencies=@{}; RSS=0; Swap=0; Available=0}
        $samples.Add($current)
    } elseif ($line -match '^THREAD (\d+) (.*?) CPU_NS=(\d+) WAIT_NS=(\d+) SLICES=(\d+)') {
        $thread = [pscustomobject]@{Tid=[int]$Matches[1]; Name=$Matches[2]; CPU=[long]$Matches[3]; Wait=[long]$Matches[4]; Allowed=''; Group=''; Core=-1; Minor=0L; Major=0L}
        $current.Threads.Add($thread)
    } elseif ($line -match '^STAT \d+ \(.*\) (.*)') {
        $fields = $Matches[1] -split '\s+'
        $thread.Core = [int]$fields[36]
        $thread.Minor = [long]$fields[7]
        $thread.Major = [long]$fields[9]
    } elseif ($line -match '^Cpus_allowed_list:\s*(.*)') { $thread.Allowed=$Matches[1]
    } elseif ($line -match '^CGROUP \d+:cpuset:(.*)') { $thread.Group=$Matches[1]
    } elseif ($line -match '^FREQ (\S+) (\S+) (\d+)') { $current.Frequencies["$($Matches[1])/$($Matches[2])"]=[int]$Matches[3]
    } elseif ($line -match '^MEM VmRSS:\s+(\d+)') { $current.RSS=[int]$Matches[1]
    } elseif ($line -match '^MEM VmSwap:\s+(\d+)') { $current.Swap=[int]$Matches[1]
    } elseif ($line -match '^SYSTEM_MEM MemAvailable:\s+(\d+)') { $current.Available=[int]$Matches[1] }
}
$intervals = for ($i=1; $i -lt $samples.Count; $i++) { $samples[$i].Time-$samples[$i-1].Time }
Write-Output "Samples=$($samples.Count) start=$($samples[0].Time) end=$($samples[-1].Time) medianInterval=$((($intervals | Sort-Object)[[int]($intervals.Count/2)]).ToString('F3'))s"
foreach ($line in Get-Content -LiteralPath (Join-Path $Directory 'analysis.log')) {
    if ($line -notmatch '^\s*([\d.]+).*BAGridAnalysis:.*placementMs=(\d+)') { continue }
    $end=[double]$Matches[1]
    $placement=[int]$Matches[2]
    $values=@{}
    foreach ($m in [regex]::Matches($line, '(\w+)=(\d+)')) { $values[$m.Groups[1].Value]=[long]$m.Groups[2].Value }
    $matchEnd=$end-($values.recommendationMs/1000)
    $matchStart=$matchEnd-($placement/1000)
    $before=$samples | Where-Object { $_.Time -le $matchStart } | Select-Object -Last 1
    $after=$samples | Where-Object { $_.Time -ge $matchEnd } | Select-Object -First 1
    if (!$before -or !$after) { Write-Output "Attempt outside capture: $end"; continue }
    $during=@($samples | Where-Object { $_.Time -ge $before.Time -and $_.Time -le $after.Time })
    Write-Output "ATTEMPT $($values.attempt) totalMs=$($values.totalMs) placementMs=$placement approxMatch=$matchStart..$matchEnd sampleWindow=$($before.Time)..$($after.Time)"
    $deltas=foreach ($t in $after.Threads) {
        $old=$before.Threads | Where-Object Tid -eq $t.Tid
        if (!$old) { continue }
        $states=@($during.Threads | Where-Object Tid -eq $t.Tid)
        [pscustomobject]@{Tid=$t.Tid; Name=$t.Name; CPUms=[math]::Round(($t.CPU-$old.CPU)/1e6,2); WaitMs=[math]::Round(($t.Wait-$old.Wait)/1e6,2); Minor=$t.Minor-$old.Minor; Major=$t.Major-$old.Major; Cores=($states.Core|Sort-Object -Unique)-join ','; Allowed=($states.Allowed|Sort-Object -Unique)-join ','; Group=($states.Group|Sort-Object -Unique)-join ','}
    }
    $deltas | Sort-Object CPUms -Descending | Format-Table -AutoSize | Out-String -Width 240 | Write-Output
    $busy=$deltas | Sort-Object CPUms -Descending | Select-Object -First 1
    foreach ($s in $during) {
        $t=$s.Threads | Where-Object Tid -eq $busy.Tid
        Write-Output "BUSY_SAMPLE time=$($s.Time) tid=$($t.Tid) cpuNs=$($t.CPU) waitNs=$($t.Wait) core=$($t.Core) allowed=$($t.Allowed) freq0=$($s.Frequencies['policy0/scaling_cur_freq']) freq6=$($s.Frequencies['policy6/scaling_cur_freq'])"
    }
}
$validMem=@($samples | Where-Object RSS -gt 0)
Write-Output "RSS_KB=$((($validMem.RSS|Measure-Object -Minimum).Minimum))..$((($validMem.RSS|Measure-Object -Maximum).Maximum)) Available_KB=$((($validMem.Available|Measure-Object -Minimum).Minimum))..$((($validMem.Available|Measure-Object -Maximum).Maximum))"
