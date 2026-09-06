param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [ValidateSet('version-backup', 'release-baseline')]
    [string]$Purpose = 'version-backup'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = (Resolve-Path -LiteralPath $ProjectRoot).Path.TrimEnd('\', '/')
$build = Get-Content -LiteralPath (Join-Path $root 'app/build.gradle.kts') -Raw
$version = [regex]::Match($build, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$code = [int][regex]::Match($build, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (!$version -or $code -le 0) { throw 'Cannot read current version.' }
$verificationCandidates = @(Get-ChildItem -LiteralPath (Join-Path $root 'app/build/outputs/apk') -Recurse -Filter verification.json |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json } |
    Where-Object { $_.VersionName -eq $version -and $_.VersionCode -eq $code -and !$_.Debuggable })
$verification = if ($Purpose -eq 'release-baseline') {
    $verificationCandidates | Where-Object {
        $_.CertificateSHA256 -eq '72ADAB147BC41A86674E1CB3A921063572A80C786D9226EBE19DD760C0958FEE' -and
        $_.APK -like '*\production-release\*'
    } | Select-Object -First 1
} else {
    $verificationCandidates | Select-Object -First 1
}
if (!$verification) { throw 'No verified non-debug APK matches the current version.' }
$apk = Get-Item -LiteralPath $verification.APK
if ((Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash -ne $verification.SHA256) {
    throw 'Existing APK checksum mismatch.'
}
$files = @(
    foreach ($relative in @('.gitignore','.gitattributes','README.md','LICENSE','THIRD_PARTY_NOTICES.md','build.gradle.kts','settings.gradle.kts',
        'gradle.properties','gradlew','gradlew.bat','app/build.gradle.kts','app/proguard-rules.pro')) {
        Get-Item -LiteralPath (Join-Path $root $relative) -Force
    }
    foreach ($relative in @('app/src','gradle','docs','dataset','texture','tools','recordings')) {
        $directory = Get-Item -LiteralPath (Join-Path $root $relative) -Force
        if ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked directory: $relative" }
        $children = @(Get-ChildItem -LiteralPath $directory.FullName -Recurse -Force)
        if ($children | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
            throw "Linked content in $relative"
        }
        $children | Where-Object { !$_.PSIsContainer }
    }
) | Sort-Object FullName -Unique
$records = @($files | ForEach-Object {
    if (!$_.FullName.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $_.Extension.ToLowerInvariant() -in @('.jks','.keystore','.p12','.pfx','.key','.pem')) {
        throw "Unsafe source or signing material: $($_.FullName)"
    }
    [pscustomobject]@{ path=$_.FullName.Substring($root.Length + 1).Replace('\','/'); bytes=$_.Length;
        sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
})
$backupRoot = Join-Path $root 'backups'
if (!(Test-Path -LiteralPath $backupRoot)) { New-Item -ItemType Directory -Path $backupRoot | Out-Null }
if ((Get-Item -LiteralPath $backupRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked backup root.' }
$destination = Join-Path $backupRoot "BA_Grid_Master-$version-$Purpose-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Path $destination | Out-Null
$zipPath = Join-Path $destination "BA_Grid_Master-$version-source.zip"
$zip = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($record in $records) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $root $record.path),
            $record.path, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $zip.Dispose() }
$zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    if ($zip.Entries.Count -ne $records.Count) { throw 'Archive count mismatch.' }
    foreach ($record in $records) {
        $entry = $zip.GetEntry($record.path)
        if (!$entry -or $entry.Length -ne $record.bytes) { throw "Missing/mismatched entry: $($record.path)" }
        $stream = $entry.Open()
        $digest = [Security.Cryptography.SHA256]::Create()
        try { $hash = [BitConverter]::ToString($digest.ComputeHash($stream)).Replace('-','') }
        finally { $stream.Dispose(); $digest.Dispose() }
        if ($hash -ne $record.sha256) { throw "Archive hash mismatch: $($record.path)" }
    }
} finally { $zip.Dispose() }
Copy-Item -LiteralPath $apk.FullName -Destination $destination
$copiedApk = Join-Path $destination $apk.Name
if ((Get-FileHash -LiteralPath $copiedApk -Algorithm SHA256).Hash -ne $verification.SHA256) { throw 'Copied APK mismatch.' }
Copy-Item -LiteralPath (Join-Path $apk.DirectoryName 'verification.json') -Destination $destination
$reports = Join-Path $destination 'test-results'
Copy-Item -LiteralPath (Join-Path $root 'app/build/test-results/testDebugUnitTest') -Destination $reports -Recurse
Get-ChildItem -LiteralPath (Join-Path $root 'app/build/reports') -Filter 'lint-results-debug.*' -File |
    Copy-Item -Destination $destination
$manifest = [ordered]@{
    versionName=$version; versionCode=$code; createdAt=[DateTimeOffset]::Now.ToString('o')
    sourceEntryCount=$records.Count; sourceEntries=$records
    archiveSHA256=(Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
    apkSHA256=$verification.SHA256
    exclusions=@('local.properties','Gradle/IDE caches','private signing keys','build intermediates','previous backups')
    purpose=$Purpose
    verificationNote='All ZIP entries and copied APK verified. Test reports are existing reports; no tests rerun by this backup.'
}
[IO.File]::WriteAllText((Join-Path $destination 'backup-manifest.json'), ($manifest | ConvertTo-Json -Depth 8))
[pscustomobject]@{ BackupDirectory=$destination; SourceFiles=$records.Count; ArchiveSHA256=$manifest.archiveSHA256;
    APK_SHA256=$verification.SHA256 } | Format-List
