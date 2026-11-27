$ErrorActionPreference = 'Stop'
$callsKeyDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) 'secrets'
New-Item -ItemType Directory -Force -Path $callsKeyDirectory | Out-Null
$callsKeyPath = Join-Path $callsKeyDirectory 'calls-encryption.key'
if (Test-Path -LiteralPath $callsKeyPath) { Write-Output 'Existing calls key preserved.'; exit 0 }
$callsKeyBytes = New-Object byte[] 32
$callsRng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $callsRng.GetBytes($callsKeyBytes) } finally { $callsRng.Dispose() }
$callsStream = [IO.File]::Open($callsKeyPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try {
    $callsEncoded = [Text.Encoding]::ASCII.GetBytes([Convert]::ToBase64String($callsKeyBytes))
    $callsStream.Write($callsEncoded,0,$callsEncoded.Length)
} finally { $callsStream.Dispose() }
Write-Output 'Calls encryption key created; keep a secure backup outside the repository.'
