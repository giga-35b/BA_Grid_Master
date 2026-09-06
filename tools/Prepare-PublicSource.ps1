param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$buildRoot = Join-Path $root 'build'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
if ((Get-Item -LiteralPath $buildRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'Refusing linked build directory.'
}
$destination = Join-Path $buildRoot ('github-preflight-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $destination | Out-Null
$indexRoot = Join-Path $destination 'ignore-check'
# An isolated empty Git index evaluates only project ignore rules. No project git init/add/commit.
& git init --quiet --template= $indexRoot
if ($LASTEXITCODE -ne 0) { throw 'Git is required to check ignore rules.' }
$gitDir = Join-Path $indexRoot '.git'
$emptyExcludes = Join-Path $gitDir 'info/exclude'
$files = @(& git -c core.quotePath=false -c "core.excludesFile=$emptyExcludes" "--git-dir=$gitDir" "--work-tree=$root" ls-files --others --exclude-standard)
if ($LASTEXITCODE -ne 0 -or !$files.Count) { throw 'Failed to enumerate source files.' }
$snapshot = Join-Path $destination 'source'
New-Item -ItemType Directory -Path $snapshot | Out-Null
$forbidden = '(?i)\.(apk|aab|apks|idsig|jks|keystore|p12|pfx|pem|key|mp4|m4v|mov|mkv|avi|webm|wmv|flv|mpg|mpeg|3gp|3g2|mts|m2ts|vob|ogv)$'
$records = foreach ($relative in $files) {
    $file = Get-Item -LiteralPath (Join-Path $root $relative) -Force
    if (!$file.FullName.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "File outside project: $relative"
    }
    for ($entry = $file; $entry.FullName -ne $root; $entry = (Get-Item -LiteralPath (Split-Path -Parent $entry.FullName) -Force)) {
        if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked source: $relative" }
    }
    if ($relative -match $forbidden -or $file.Length -ge 100MB) { throw "Forbidden or oversized source: $relative" }
    $target = Join-Path $snapshot $relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $target
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $hash) { throw "Copy mismatch: $relative" }
    [pscustomobject]@{ path=$relative; bytes=$file.Length; sha256=$hash }
}
$records | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $destination 'source-manifest.json') -Encoding utf8
[pscustomobject]@{
    SourceDirectory=$snapshot
    FileCount=$records.Count
    MiB=[math]::Round(($records | Measure-Object bytes -Sum).Sum / 1MB, 2)
    Images=@($records | Where-Object path -Match '(?i)\.(png|jpe?g|webp|bmp|gif|heic)$').Count
    Note='Independent source snapshot; main project Git state unchanged. No commit, upload, APK or video.'
} | Format-List
