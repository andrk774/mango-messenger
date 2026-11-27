$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$directory = Join-Path $root 'secrets/kubernetes'
$target = Join-Path $directory 'runtime.env'
if (Test-Path -LiteralPath $target) { throw 'runtime.env already exists; preserving existing credentials.' }
New-Item -ItemType Directory -Force -Path $directory | Out-Null
# Restrict the directory before creating credentials. Do not print their contents.
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $directory /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Cannot restrict secrets directory permissions.' }
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $values = foreach ($name in @('MANGO_DB_PASSWORD', 'MANGO_JWT_SECRET')) {
        $bytes = New-Object byte[] 48
        $rng.GetBytes($bytes)
        $name + '=' + [Convert]::ToBase64String($bytes)
    }
    [IO.File]::WriteAllLines($target, $values, [Text.Encoding]::ASCII)
} finally { $rng.Dispose() }
Write-Output 'Created secrets/kubernetes/runtime.env. Keep it private; existing Compose credentials were not changed.'
& (Join-Path $root 'scripts/initialize-smtp-key.ps1') -KeyPath (Join-Path $directory 'smtp-encryption.key')
