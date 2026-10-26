[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v(?:0|[1-9][0-9]{0,2})\.(?:0|[1-9][0-9]{0,2})\.(?:0|[1-9][0-9]{0,2})$')]
    [string]$Tag,

    [Parameter(Mandatory = $true)]
    [string]$KeystorePath,

    [string]$KeyAlias = $env:LAUNCHER_KEY_ALIAS,
    [Security.SecureString]$StorePassword,
    [Security.SecureString]$KeyPassword
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$keystoreFile = (Resolve-Path -LiteralPath $KeystorePath -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $keystoreFile -PathType Leaf)) { throw 'The signing keystore must be an existing file.' }
if ([string]::IsNullOrWhiteSpace($KeyAlias)) { throw 'Supply -KeyAlias or set LAUNCHER_KEY_ALIAS.' }
$numbers = $Tag.Substring(1).Split('.') | ForEach-Object { [int]$_ }
$versionCode = [long]$numbers[0] * 1000000 + [long]$numbers[1] * 1000 + $numbers[2]
if ($versionCode -lt 1 -or $versionCode -gt 2100000000) { throw 'The derived Android version code is outside its supported range.' }
$sdkRoot = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($sdkRoot)) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($sdkRoot)) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
$apksigner = Join-Path $sdkRoot 'build-tools/37.0.0/apksigner.bat'
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw 'Install Android SDK Build Tools 37.0.0 before signing.' }

# Gradle reads credentials from the process environment. No password is written to a file or argument list.
$variableNames = @('LAUNCHER_STORE_FILE', 'LAUNCHER_STORE_PASSWORD', 'LAUNCHER_KEY_ALIAS', 'LAUNCHER_KEY_PASSWORD', 'LAUNCHER_VERSION_NAME', 'LAUNCHER_VERSION_CODE')
$originalEnvironment = @{}
foreach ($variableName in $variableNames) { $originalEnvironment[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process') }

try {
    if ($null -ne $StorePassword) {
        $env:LAUNCHER_STORE_PASSWORD = [Net.NetworkCredential]::new('', $StorePassword).Password
    } elseif ([string]::IsNullOrWhiteSpace($env:LAUNCHER_STORE_PASSWORD)) {
        $StorePassword = Read-Host 'Keystore password' -AsSecureString
        $env:LAUNCHER_STORE_PASSWORD = [Net.NetworkCredential]::new('', $StorePassword).Password
    }
    if ($null -ne $KeyPassword) {
        $env:LAUNCHER_KEY_PASSWORD = [Net.NetworkCredential]::new('', $KeyPassword).Password
    } elseif ([string]::IsNullOrWhiteSpace($env:LAUNCHER_KEY_PASSWORD)) {
        $KeyPassword = Read-Host 'Signing key password' -AsSecureString
        $env:LAUNCHER_KEY_PASSWORD = [Net.NetworkCredential]::new('', $KeyPassword).Password
    }
    if ([string]::IsNullOrWhiteSpace($env:LAUNCHER_STORE_PASSWORD) -or [string]::IsNullOrWhiteSpace($env:LAUNCHER_KEY_PASSWORD)) {
        throw 'Both signing passwords are required.'
    }
    $env:LAUNCHER_STORE_FILE = $keystoreFile
    $env:LAUNCHER_KEY_ALIAS = $KeyAlias
    $env:LAUNCHER_VERSION_NAME = $Tag.Substring(1)
    $env:LAUNCHER_VERSION_CODE = $versionCode.ToString([Globalization.CultureInfo]::InvariantCulture)
    Push-Location -LiteralPath $projectRoot
    try {
        & .\gradlew.bat --no-daemon lintDebug
        if ($LASTEXITCODE -ne 0) { throw 'Android checks failed; no release was produced.' }
        & .\gradlew.bat --no-daemon assembleRelease
        if ($LASTEXITCODE -ne 0) { throw 'The signed release build failed.' }
        $releaseApk = Join-Path $projectRoot 'app/build/outputs/apk/release/app-release.apk'
        if (-not (Test-Path -LiteralPath $releaseApk -PathType Leaf)) { throw 'The expected signed APK was not generated.' }
        $signatureReport = & $apksigner verify --verbose --print-certs $releaseApk
        if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
        $expectedSigner = (Get-Content -Raw (Join-Path $projectRoot 'signing-certificate.sha256')).Trim()
        $signers = @([regex]::Matches(($signatureReport -join [Environment]::NewLine), 'certificate SHA-256 digest: ([a-fA-F0-9]{64})') | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Select-Object -Unique)
        if ($signers.Count -ne 1 -or $signers[0] -ne $expectedSigner) { throw 'Release signing identity differs from the pinned certificate.' }
        $artifactDirectory = Join-Path $projectRoot 'artifacts'
        New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
        $artifactApk = Join-Path $artifactDirectory 'launcher.apk'
        Copy-Item -LiteralPath $releaseApk -Destination $artifactApk -Force
        $apkDigest = (Get-FileHash -LiteralPath $artifactApk -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::WriteAllText((Join-Path $artifactDirectory 'SHA256SUMS.txt'), "$apkDigest  launcher.apk`n", [Text.UTF8Encoding]::new($false))
        Write-Host "Signed $Tag (Android version code $versionCode): $artifactApk"
    } finally { Pop-Location }
} finally {
    foreach ($variableName in $variableNames) { [Environment]::SetEnvironmentVariable($variableName, $originalEnvironment[$variableName], 'Process') }
    $StorePassword = $null
    $KeyPassword = $null
}
