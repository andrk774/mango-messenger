param([string]$SevenZip = 'C:\Program Files\7-Zip\7z.exe')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$source = Join-Path $root 'secrets/release'
$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$destination = Join-Path $root "artifacts/signing-backups/$stamp"
$recoveryDirectory = Join-Path $root "secrets/signing-recovery/$stamp"
if (-not (Test-Path -LiteralPath $SevenZip)) { throw '7-Zip is required.' }
$secure = (Get-Content -LiteralPath (Join-Path $source 'password.dpapi') -Raw).Trim() | ConvertTo-SecureString
$storePassword = [Net.NetworkCredential]::new('', $secure).Password
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$recoveryPassword = [Convert]::ToBase64String($bytes)
New-Item -ItemType Directory -Path $destination,$recoveryDirectory | Out-Null
$archive = Join-Path $destination 'mango-signing-key.7z'
$payload = @{
    format = 1
    alias = 'mango-release'
    keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $source 'mango-release.p12')))
    keystorePassword = $storePassword
} | ConvertTo-Json -Compress
# Plaintext payload travels through stdin only, never through a staging file.
$payload | & $SevenZip a -t7z $archive '-sisigning-key.json' '-mhe=on' "-p$recoveryPassword" -bso0 -bsp0
if ($LASTEXITCODE -ne 0) { throw 'Archive creation failed; existing files preserved.' }
$restored = & $SevenZip x $archive -so "-p$recoveryPassword" -bsp0
if ($LASTEXITCODE -ne 0) { throw 'Archive extraction check failed.' }
$restoredPayload = ($restored -join "`n") | ConvertFrom-Json
if ($restoredPayload.keystoreBase64 -cne (($payload | ConvertFrom-Json).keystoreBase64) -or
    $restoredPayload.keystorePassword -cne $storePassword) { throw 'Archive round-trip mismatch.' }
# Store the recovery secret separately from the archive. Never print its contents.
[IO.File]::WriteAllText((Join-Path $recoveryDirectory 'recovery-password.txt'), $recoveryPassword, [Text.UTF8Encoding]::new($false))
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
[IO.File]::WriteAllText((Join-Path $destination 'SHA256.txt'), "$hash  mango-signing-key.7z`n")
@'
Encrypted signing-key backup (7-Zip AES-256, encrypted filenames).
Keep the recovery password separately, ideally in a password manager or offline safe.
On a recovery computer, extract signing-key.json using 7-Zip and the recovery password.
Decode keystoreBase64 into mango-release.p12. Use keystorePassword and alias from the JSON.
The restored key does not depend on the original Windows account or DPAPI.
Do not publish the extracted JSON, keystore, or recovery password.
Copy this archive to a separate device. A copy on the same laptop is not disaster recovery.
'@ | Set-Content -LiteralPath (Join-Path $destination 'README.txt') -Encoding UTF8
Write-Output "Verified archive: $archive"
Write-Output "Recovery password file (keep separate): $recoveryDirectory\recovery-password.txt"
$storePassword = $null; $recoveryPassword = $null; $payload = $null; $restored = $null; $restoredPayload = $null
