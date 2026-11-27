param([string]$KeyPath)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$path = if ($KeyPath) { [IO.Path]::GetFullPath($KeyPath) } else { Join-Path $root 'secrets/smtp-encryption.key' }
$directory = Split-Path $path -Parent
if (Test-Path -LiteralPath $path) { throw 'SMTP key already exists. It must not be overwritten.' }
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$bytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $encoded = [Text.Encoding]::ASCII.GetBytes([Convert]::ToBase64String($bytes))
    $stream.Write($encoded, 0, $encoded.Length)
} finally { $stream.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
$account = "$env:USERDOMAIN\$env:USERNAME"
& icacls $path /inheritance:r /grant:r "${account}:F" '*S-1-5-18:F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Key created but ACL setup failed; preserve the file and fix permissions.' }
Write-Output 'SMTP key created. Back it up securely; its value is not displayed.'
