param(
    [Parameter(Mandatory=$true)][string]$BackupDirectory,
    [string]$PostgresContainer = 'mango-postgres-1',
    [string]$DatabaseUser = 'mango'
)
$ErrorActionPreference = 'Stop'
$directory = (Resolve-Path -LiteralPath $BackupDirectory).Path
$manifest = Get-Content -LiteralPath (Join-Path $directory 'manifest.json') -Raw | ConvertFrom-Json
foreach ($entry in $manifest) {
    $path = [IO.Path]::GetFullPath((Join-Path $directory $entry.Path))
    if (-not $path.StartsWith($directory + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Manifest path escapes backup directory'
    }
    if ((Get-FileHash -LiteralPath $path).Hash -ne $entry.SHA256) { throw "Checksum mismatch: $($entry.Path)" }
}
# New isolated database only: never restore over application data or drop databases.
$name = 'mango_restore_' + [guid]::NewGuid().ToString('N')
$remoteDump = "/tmp/$name.dump"
& docker cp (Join-Path $directory 'database.dump') "${PostgresContainer}:$remoteDump"
if ($LASTEXITCODE -ne 0) { throw 'Copy failed' }
& docker exec $PostgresContainer createdb -U $DatabaseUser $name
if ($LASTEXITCODE -ne 0) { throw 'Creating isolated database failed' }
& docker exec $PostgresContainer pg_restore -U $DatabaseUser -d $name --exit-on-error --no-owner $remoteDump
if ($LASTEXITCODE -ne 0) { throw "Restore failed; isolated database retained: $name" }
& docker exec $PostgresContainer psql -U $DatabaseUser -d $name -c 'SELECT count(*) AS migration_count FROM flyway_schema_history WHERE success;'
if ($LASTEXITCODE -ne 0) { throw 'Restored schema check failed' }
Write-Output "Checksums and restore verified. Isolated database retained: $name"
