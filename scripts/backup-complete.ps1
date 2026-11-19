param([switch]$Verify)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$python = Join-Path $root 'artifacts/backup-tools/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'Prepare artifacts/backup-tools Python venv with cryptography==46.0.3; see docs/OPERATIONS.md' }
$mode = if ($Verify) { 'backup-and-verify' } else { 'backup' }
& $python (Join-Path $PSScriptRoot 'backup_restore.py') $mode
if ($LASTEXITCODE -ne 0) { throw 'Backup/restore verification failed; files retained for diagnosis' }
