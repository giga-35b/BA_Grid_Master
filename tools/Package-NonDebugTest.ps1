param(
    [string]$JavaPath = '',
    [string]$BuildTools = '',
    [string]$DebugKeyStore = '',
    [ValidatePattern('^[a-z0-9-]+$')]
    [string]$Variant = 'nondebug-test',
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Resolve-AndroidTools.ps1')
if (!$JavaPath) { $JavaPath = Resolve-JavaExecutable }
if (!$BuildTools) { $BuildTools = Join-Path (Resolve-AndroidSdk) 'build-tools/37.0.0' }
if (!$DebugKeyStore) {
    $DebugKeyStore = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android/debug.keystore'
}
$buildConfig = Get-Content -LiteralPath (Join-Path $workspace 'app/build.gradle.kts') -Raw
$versionName = [regex]::Match($buildConfig, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$versionCode = [int][regex]::Match($buildConfig, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if ($versionName -notmatch '^\d+\.\d+\.\d+$' -or $versionCode -le 0) { throw 'Cannot read build version.' }
$unsigned = Join-Path $workspace 'app/build/outputs/apk/release/app-release-unsigned.apk'
$reference = Join-Path $workspace 'app/build/outputs/apk/debug/app-debug.apk'
$destination = Join-Path $workspace "app/build/outputs/apk/$Variant"
$apk = Join-Path $destination "BA_Grid_Master-$versionName-$Variant.apk"
$signer = Join-Path $BuildTools 'lib/apksigner.jar'
$aapt = Join-Path $BuildTools 'aapt2.exe'
$align = Join-Path $BuildTools 'zipalign.exe'
if (!$VerifyOnly -and (Test-Path -LiteralPath $apk)) { throw 'Test APK already exists; refusing to overwrite it. Use -VerifyOnly to recheck.' }
if ($VerifyOnly -and !(Test-Path -LiteralPath $apk)) { throw 'No test APK to verify.' }
foreach ($file in @($unsigned, $reference, $DebugKeyStore, $JavaPath, $signer, $aapt, $align)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing: $file" }
}
New-Item -ItemType Directory -Force -Path $destination | Out-Null
# Sign a separate artifact, never configure the production release to use the debug key.
# Android's standard disposable debug-key credentials are not production secrets.
if (!$VerifyOnly) {
    & $JavaPath -jar $signer sign --ks $DebugKeyStore --ks-key-alias AndroidDebugKey --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false --out $apk $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'Test APK signing failed.' }
}
$signature = & $JavaPath -jar $signer verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'Test APK signature verification failed.' }
$referenceSignature = & $JavaPath -jar $signer verify --print-certs $reference
if ($LASTEXITCODE -ne 0) { throw 'Reference debug APK signature verification failed.' }
$certificatePattern = 'certificate SHA-256 digest: ([0-9a-fA-F]{64})'
$signedCertificate = [regex]::Match(($signature -join "`n"), $certificatePattern).Groups[1].Value
$referenceCertificate = [regex]::Match(($referenceSignature -join "`n"), $certificatePattern).Groups[1].Value
if (!$signedCertificate -or $signedCertificate -ne $referenceCertificate) { throw 'Signing certificate does not match the existing Debug APK.' }
$badging = & $aapt dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'APK badging check failed.' }
$manifest = & $aapt dump xmltree $apk --file AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Binary manifest check failed.' }
$badgingText = $badging -join "`n"
$manifestText = $manifest -join "`n"
if ($badgingText -match 'application-debuggable' -or $manifestText -match 'android:(debuggable|testOnly).*0xffffffff') { throw 'APK is still debuggable or testOnly.' }
$identity = "package: name='com.bagridmaster.app' versionCode='$versionCode' versionName='$versionName'"
if (!$badgingText.Contains($identity)) { throw 'Unexpected package or version identity.' }
$alignment = & $align -c -P 16 -v 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment check failed.' }
$signature | Set-Content -LiteralPath (Join-Path $destination 'signature-verification.txt') -Encoding utf8
$badging | Set-Content -LiteralPath (Join-Path $destination 'apk-badging.txt') -Encoding utf8
$manifest | Set-Content -LiteralPath (Join-Path $destination 'binary-manifest.txt') -Encoding utf8
$alignment | Set-Content -LiteralPath (Join-Path $destination 'alignment-verification.txt') -Encoding utf8
$result = [pscustomobject]@{
    APK = $apk
    Bytes = (Get-Item -LiteralPath $apk).Length
    SHA256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
    UnsignedReleaseSHA256 = (Get-FileHash -LiteralPath $unsigned -Algorithm SHA256).Hash
    ReferenceDebugSHA256 = (Get-FileHash -LiteralPath $reference -Algorithm SHA256).Hash
    CertificateSHA256 = $signedCertificate
    Package = 'com.bagridmaster.app'
    VersionName = $versionName
    VersionCode = $versionCode
    Debuggable = $false
    TestOnly = $false
    InstalledOnPhone = $false
    Purpose = 'Non-debug performance comparison only; signed with the existing debug key, not a production release identity.'
}
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'verification.json') -Encoding utf8
$result | Format-List
