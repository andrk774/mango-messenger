param(
    [string]$PostgresContainer = 'mango-postgres-1',
    [string]$BackendContainer = 'mango-backend-1',
    [string]$Database = 'mango',
    [string]$DatabaseUser = 'mango'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$stamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$destination = Join-Path $root "artifacts/backups/$stamp"
New-Item -ItemType Directory -Path $destination | Out-Null
$remoteDump = "/tmp/mango-backup-$stamp.dump"
& docker exec $PostgresContainer pg_dump -U $DatabaseUser -d $Database -Fc -f $remoteDump
if ($LASTEXITCODE -ne 0) { throw 'Database backup failed' }
& docker cp "${PostgresContainer}:$remoteDump" (Join-Path $destination 'database.dump')
if ($LASTEXITCODE -ne 0) { throw 'Copying database backup failed' }
& docker cp "${BackendContainer}:/data/attachments" (Join-Path $destination 'attachments')
if ($LASTEXITCODE -ne 0) { throw 'Copying attachments failed' }
Get-ChildItem $destination -Recurse -File | ForEach-Object {
    [pscustomobject]@{Path=$_.FullName.Substring($destination.Length+1); SHA256=(Get-FileHash -LiteralPath $_.FullName).Hash}
} | ConvertTo-Json -Depth 3 | Set-Content -Encoding UTF8 (Join-Path $destination 'manifest.json')
Write-Output "Backup saved: $destination"
Write-Output 'For a consistent database/attachment pair, run while application writes are paused. Secrets are not included.'
