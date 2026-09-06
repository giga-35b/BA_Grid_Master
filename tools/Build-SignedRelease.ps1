param(
    [Parameter(Mandatory = $true)]
    [string]$KeyStore,
    [string]$Alias = 'ba_grid_master_release',
    [string]$JavaHome = '',
    [string]$BuildTools = ''
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Resolve-AndroidTools.ps1')

function ConvertFrom-SecureValue {
    param([Parameter(Mandatory = $true)][Security.SecureString]$Value)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

$keyStorePath = (Resolve-Path -LiteralPath $KeyStore -ErrorAction Stop).Path
if (!(Test-Path -LiteralPath $keyStorePath -PathType Leaf)) { throw "Keystore is not a file: $keyStorePath" }
$workspacePrefix = $workspace.TrimEnd('\') + '\'
if ($keyStorePath.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Production keystore must remain outside the project directory.'
}

$previousJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
if ($JavaHome) {
    $resolvedJavaHome = (Resolve-Path -LiteralPath $JavaHome -ErrorAction Stop).Path
    if (!(Test-Path -LiteralPath (Join-Path $resolvedJavaHome 'bin/java.exe') -PathType Leaf)) {
        throw "JavaHome does not contain bin/java.exe: $resolvedJavaHome"
    }
    $env:JAVA_HOME = $resolvedJavaHome
}
$javaPath = Resolve-JavaExecutable
if (!$BuildTools) { $BuildTools = Join-Path (Resolve-AndroidSdk) 'build-tools/37.0.0' }
$signer = Join-Path $BuildTools 'lib/apksigner.jar'
$aapt = Join-Path $BuildTools 'aapt2.exe'
$align = Join-Path $BuildTools 'zipalign.exe'
foreach ($file in @($javaPath, $signer, $aapt, $align)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing build tool: $file" }
}

$storePasswordSecure = Read-Host 'Keystore password' -AsSecureString
$keyPasswordSecure = Read-Host 'Key password' -AsSecureString
$storePasswordPlain = ConvertFrom-SecureValue $storePasswordSecure
$keyPasswordPlain = ConvertFrom-SecureValue $keyPasswordSecure
$signingVariables = @(
    'BA_GRID_MASTER_KEYSTORE_FILE',
    'BA_GRID_MASTER_KEYSTORE_PASSWORD',
    'BA_GRID_MASTER_KEY_ALIAS',
    'BA_GRID_MASTER_KEY_PASSWORD'
)
$previousValues = @{}
foreach ($name in $signingVariables) {
    $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $env:BA_GRID_MASTER_KEYSTORE_FILE = $keyStorePath
    $env:BA_GRID_MASTER_KEYSTORE_PASSWORD = $storePasswordPlain
    $env:BA_GRID_MASTER_KEY_ALIAS = $Alias
    $env:BA_GRID_MASTER_KEY_PASSWORD = $keyPasswordPlain

    & (Join-Path $workspace 'gradlew.bat') :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Release build or verification task failed.' }

    $buildConfig = Get-Content -LiteralPath (Join-Path $workspace 'app/build.gradle.kts') -Raw
    $versionName = [regex]::Match($buildConfig, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    $versionCode = [int][regex]::Match($buildConfig, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    if ($versionName -notmatch '^\d+\.\d+\.\d+$' -or $versionCode -le 0) { throw 'Cannot read build version.' }

    $builtApk = Join-Path $workspace 'app/build/outputs/apk/release/app-release.apk'
    if (!(Test-Path -LiteralPath $builtApk -PathType Leaf)) { throw "Signed APK was not produced: $builtApk" }

    $signature = & $javaPath -jar $signer verify --verbose --print-certs $builtApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $certificatePattern = 'certificate SHA-256 digest: ([0-9a-fA-F]{64})'
    $certificateSha256 = [regex]::Match(($signature -join "`n"), $certificatePattern).Groups[1].Value.ToUpperInvariant()
    $expectedCertificateSha256 = '72ADAB147BC41A86674E1CB3A921063572A80C786D9226EBE19DD760C0958FEE'
    if ($certificateSha256 -ne $expectedCertificateSha256) {
        throw "Unexpected signing certificate: $certificateSha256"
    }

    $badging = & $aapt dump badging $builtApk
    if ($LASTEXITCODE -ne 0) { throw 'APK badging check failed.' }
    $manifest = & $aapt dump xmltree $builtApk --file AndroidManifest.xml
    if ($LASTEXITCODE -ne 0) { throw 'Binary manifest check failed.' }
    $badgingText = $badging -join "`n"
    $manifestText = $manifest -join "`n"
    $identity = "package: name='com.bagridmaster.app' versionCode='$versionCode' versionName='$versionName'"
    if (!$badgingText.Contains($identity)) { throw 'Unexpected package or version identity.' }
    if ($badgingText -match 'application-debuggable' -or $manifestText -match 'android:(debuggable|testOnly).*0xffffffff') {
        throw 'APK is debuggable or testOnly.'
    }
    & $align -c -P 16 -v 4 $builtApk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment check failed.' }

    $destination = Join-Path $workspace 'app/build/outputs/apk/production-release'
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    $finalApk = Join-Path $destination "BA_Grid_Master-$versionName.apk"
    Copy-Item -LiteralPath $builtApk -Destination $finalApk -Force
    $result = [pscustomobject]@{
        APK = $finalApk
        Bytes = (Get-Item -LiteralPath $finalApk).Length
        SHA256 = (Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash
        CertificateSHA256 = $certificateSha256
        Package = 'com.bagridmaster.app'
        VersionName = $versionName
        VersionCode = $versionCode
        Debuggable = $false
        TestOnly = $false
    }
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'verification.json') -Encoding utf8
    $signature | Set-Content -LiteralPath (Join-Path $destination 'signature-verification.txt') -Encoding utf8
    $result | Format-List
}
finally {
    foreach ($name in $signingVariables) {
        [Environment]::SetEnvironmentVariable($name, $previousValues[$name], 'Process')
    }
    $storePasswordPlain = $null
    $keyPasswordPlain = $null
    $storePasswordSecure.Dispose()
    $keyPasswordSecure.Dispose()
    if ($JavaHome) {
        [Environment]::SetEnvironmentVariable('JAVA_HOME', $previousJavaHome, 'Process')
    }
}
