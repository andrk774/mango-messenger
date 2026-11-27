param(
    [string]$ServerUrl = 'https://mango.home.arpa/',
    [string]$ServerIp = '192.168.1.121',
    [string]$Jdk = 'C:\Program Files\Android\Android Studio\jbr',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$keyDirectory = Join-Path $root 'secrets/release'
New-Item -ItemType Directory -Force -Path $keyDirectory | Out-Null
$keystore = Join-Path $keyDirectory 'mango-release.p12'
$passwordFile = Join-Path $keyDirectory 'password.dpapi'
if ((Test-Path $keystore) -ne (Test-Path $passwordFile)) { throw 'Incomplete signing configuration; existing files are preserved.' }
if (-not (Test-Path $keystore)) {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $password = [Convert]::ToBase64String($bytes)
    $secure = ConvertTo-SecureString $password -AsPlainText -Force
    $secure | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordFile -Encoding ASCII
} else {
    $secure = (Get-Content -LiteralPath $passwordFile -Raw).Trim() | ConvertTo-SecureString
    $password = [Net.NetworkCredential]::new('', $secure).Password
}
$previousJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $Jdk
    $env:MANGO_KEYSTORE_PASSWORD = $password
    $env:MANGO_KEY_PASSWORD = $password
    $env:MANGO_KEYSTORE_PATH = $keystore
    $env:MANGO_KEY_ALIAS = 'mango-release'
    if (-not (Test-Path $keystore)) {
        & "$Jdk/bin/keytool.exe" -genkeypair -storetype PKCS12 -keystore $keystore -alias mango-release -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Mango Local Release' -storepass:env MANGO_KEYSTORE_PASSWORD -keypass:env MANGO_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed. Existing files preserved for inspection.' }
    }
    $gradleArguments = @('-p', "$root/android", ':app:assembleRelease', "-Pmango.serverUrl=$ServerUrl", "-Pmango.serverIp=$ServerIp", '--console=plain')
    if ($Offline) { $gradleArguments += '--offline' }
    # Windows PowerShell can turn harmless native stderr warnings into terminating errors.
    $previousErrorPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & "$root/android/gradlew.bat" @gradleArguments
        $buildExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorPreference
    }
    if ($buildExitCode -ne 0) { throw "Release build failed ($buildExitCode)" }
    Write-Output 'Release built. Signing password is protected with Windows DPAPI for this Windows account.'
} finally {
    $env:JAVA_HOME = $previousJava
    $env:MANGO_KEYSTORE_PASSWORD = $null
    $env:MANGO_KEY_PASSWORD = $null
    $env:MANGO_KEYSTORE_PATH = $null
    $env:MANGO_KEY_ALIAS = $null
    $password = $null
}
