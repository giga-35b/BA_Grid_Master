param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

# Run only after the baseline build/test command has succeeded. No build, signing or publishing here.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path.TrimEnd('\', '/')
$version = '1.0.0'
$versionCode = 2
$buildScript = Get-Content -LiteralPath (Join-Path $projectPath 'app/build.gradle.kts') -Raw
if ($buildScript -notmatch 'versionName\s*=\s*"1\.0\.0"' -or $buildScript -notmatch 'versionCode\s*=\s*2\b') {
    throw 'This backup script freezes the 1.0.0 / versionCode 2 baseline only.'
}

$unitReports = @(Get-ChildItem -LiteralPath (Join-Path $projectPath 'app/build/test-results/testDebugUnitTest') -Filter 'TEST-*.xml' -File)
if ($unitReports.Count -eq 0) { throw 'No unit test reports found.' }
$testSummary = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
foreach ($file in $unitReports) {
    [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($key in @('tests', 'failures', 'errors', 'skipped')) {
        $testSummary[$key] += [int]$report.testsuite.$key
    }
}
if ($testSummary.failures -ne 0 -or $testSummary.errors -ne 0) { throw 'Unit tests are not passing.' }
$lintSummary = [ordered]@{}
foreach ($variant in @('debug', 'release')) {
    $lintText = Get-Content -LiteralPath (Join-Path $projectPath "app/build/reports/lint-results-$variant.txt") -Raw
    if ($lintText -notmatch '(?m)^0 errors, (\d+) warnings\s*$' -and $lintText -notmatch '^No issues found\.?\s*$') {
        throw "Lint for $variant has errors or an unrecognized summary."
    }
    $lintSummary[$variant] = ($lintText.Trim() -split '\r?\n')[-1]
}

$apkSources = @(
    @{ dir = 'app/build/outputs/apk/debug'; name = "BA_Grid_Master-$version-debug.apk"; validateVersion = $true },
    @{ dir = 'app/build/outputs/apk/release'; name = "BA_Grid_Master-$version-release-unsigned.apk"; validateVersion = $true },
    @{ dir = 'app/build/outputs/apk/androidTest/debug'; name = "BA_Grid_Master-$version-androidTest.apk"; validateVersion = $false }
)
foreach ($apk in $apkSources) {
    $apk.metadataPath = Join-Path $projectPath ($apk.dir + '/output-metadata.json')
    $metadata = Get-Content -LiteralPath $apk.metadataPath -Raw | ConvertFrom-Json
    if (@($metadata.elements).Count -ne 1) { throw "Expected one APK for $($apk.dir)." }
    $element = @($metadata.elements)[0]
    if ($apk.validateVersion -and ($element.versionName -ne $version -or $element.versionCode -ne $versionCode -or
        $metadata.applicationId -ne 'com.bagridmaster.app')) { throw "Stale APK metadata in $($apk.dir)." }
    if ([IO.Path]::GetFileName($element.outputFile) -ne $element.outputFile) { throw 'Unsafe APK output path.' }
    if ($apk.dir.EndsWith('/release') -and !$element.outputFile.EndsWith('-unsigned.apk')) {
        throw 'Release signing configuration changed; review the baseline signing notes first.'
    }
    $apk.sourcePath = Join-Path $projectPath ($apk.dir + '/' + $element.outputFile)
    if (!(Test-Path -LiteralPath $apk.sourcePath -PathType Leaf)) { throw "Missing $($apk.sourcePath)." }
}

# Explicit allowlist: no cache directories, local.properties, private signing material or old backups.
$sourceFiles = @(
    foreach ($relative in @('.gitignore', 'README.md', 'LICENSE', 'build.gradle.kts', 'settings.gradle.kts',
        'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle.kts', 'app/proguard-rules.pro')) {
        Get-Item -LiteralPath (Join-Path $projectPath $relative) -Force
    }
    foreach ($relative in @('app/src', 'gradle', 'docs', 'dataset', 'texture', 'tools')) {
        $directory = Get-Item -LiteralPath (Join-Path $projectPath $relative) -Force
        if ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked source directory: $relative" }
        Get-ChildItem -LiteralPath $directory.FullName -Recurse -File -Force
    }
) | Sort-Object FullName -Unique
$sourceRecords = @(
    foreach ($file in $sourceFiles) {
        if (!$file.FullName.StartsWith($projectPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Source file escaped project root.'
        }
        if (($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            $file.Extension.ToLowerInvariant() -in @('.jks', '.keystore', '.p12', '.pfx', '.key', '.pem')) {
            throw "Linked file or possible signing material requires manual review: $($file.FullName)"
        }
        [pscustomobject]@{
            path = $file.FullName.Substring($projectPath.Length + 1).Replace('\', '/')
            bytes = $file.Length
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        }
    }
)

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupRoot = Join-Path $projectPath 'backups'
if (!(Test-Path -LiteralPath $backupRoot)) { New-Item -ItemType Directory -Path $backupRoot | Out-Null }
if ((Get-Item -LiteralPath $backupRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Backup root must not be a link.' }
$destination = Join-Path $backupRoot "BA_Grid_Master-$version-baseline-$stamp"
# No -Force: never overwrite a previous baseline.
New-Item -ItemType Directory -Path $destination | Out-Null
foreach ($relative in @('artifacts', 'verification', 'verification/unit-tests')) {
    New-Item -ItemType Directory -Path (Join-Path $destination $relative) | Out-Null
}
$archivePath = Join-Path $destination "BA_Grid_Master-$version-source.zip"
Write-Output "Archiving $($sourceRecords.Count) source files into $archivePath"
$archive = [IO.Compression.ZipFile]::Open($archivePath, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($record in $sourceRecords) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $projectPath $record.path),
            $record.path, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }

# Verify every entry against the pre-archive source digest, detecting missing files and concurrent edits.
$archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    if ($archive.Entries.Count -ne $sourceRecords.Count) { throw 'Archive entry count mismatch.' }
    foreach ($record in $sourceRecords) {
        $entry = $archive.GetEntry($record.path)
        if ($null -eq $entry -or $entry.Length -ne $record.bytes) { throw "Archive entry mismatch: $($record.path)" }
        $stream = $entry.Open()
        $digest = [Security.Cryptography.SHA256]::Create()
        try { $hash = [BitConverter]::ToString($digest.ComputeHash($stream)).Replace('-', '') }
        finally { $stream.Dispose(); $digest.Dispose() }
        if ($hash -ne $record.sha256) { throw "Archive checksum mismatch: $($record.path)" }
    }
} finally { $archive.Dispose() }

foreach ($apk in $apkSources) {
    Copy-Item -LiteralPath $apk.sourcePath -Destination (Join-Path $destination ('artifacts/' + $apk.name))
    Copy-Item -LiteralPath $apk.metadataPath -Destination (Join-Path $destination ('artifacts/' + $apk.name + '.metadata.json'))
}
foreach ($file in $unitReports) { Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $destination 'verification/unit-tests') }
Get-ChildItem -LiteralPath (Join-Path $projectPath 'app/build/reports') -File |
    Where-Object { $_.Name -match '^lint-results-(debug|release)\.' } |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $destination 'verification') }
Copy-Item -LiteralPath (Join-Path $projectPath 'docs/RELEASE_BASELINE_1.0.0.md') -Destination (Join-Path $destination 'README.md')
[IO.File]::WriteAllText((Join-Path $destination 'source-manifest.json'), (ConvertTo-Json -InputObject $sourceRecords -Depth 5), [Text.UTF8Encoding]::new($false))
$payloadRecords = @(
    Get-ChildItem -LiteralPath $destination -Recurse -File -Force | Sort-Object FullName | ForEach-Object {
        [pscustomobject]@{
            path = $_.FullName.Substring($destination.Length + 1).Replace('\', '/')
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        }
    }
)
$manifest = [ordered]@{
    project = 'BA_Grid_Master'; versionName = $version; versionCode = $versionCode
    applicationId = 'com.bagridmaster.app'; createdAt = [DateTimeOffset]::Now.ToString('o')
    archiveVerifiedEntryCount = $sourceRecords.Count; sourceBytes = ($sourceRecords | Measure-Object bytes -Sum).Sum
    unitTests = $testSummary; lint = $lintSummary; instrumentationTests = 'Compiled, not run on device'
    signing = @{ debug = 'Development signing only'; release = 'Unsigned; configure long-term release signing before distribution' }
    buildTasks = ':app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --no-daemon'
    exclusions = @('Gradle/IDE caches', 'local.properties', 'signing private keys', 'build intermediates', 'previous backups')
    files = $payloadRecords
}
$manifestPath = Join-Path $destination 'backup-manifest.json'
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
[IO.File]::WriteAllText((Join-Path $destination 'backup-manifest.sha256'), "$manifestHash  backup-manifest.json`n", [Text.UTF8Encoding]::new($false))
foreach ($record in $payloadRecords) {
    $copied = Get-Item -LiteralPath (Join-Path $destination $record.path)
    if ($copied.Length -ne $record.bytes -or (Get-FileHash -LiteralPath $copied.FullName -Algorithm SHA256).Hash -ne $record.sha256) {
        throw "Backup payload verification failed: $($record.path)"
    }
}
Write-Output "Verified $($sourceRecords.Count) archive entries and $($payloadRecords.Count) backup payloads."
[pscustomobject]@{ BackupDirectory = $destination; Version = $version; VersionCode = $versionCode; UnitTests = $testSummary.tests; ManifestSHA256 = $manifestHash } | Format-List
