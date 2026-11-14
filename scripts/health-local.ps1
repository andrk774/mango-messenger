param([string]$Url = 'http://127.0.0.1:8081/v1/health')
$ErrorActionPreference = 'Stop'
try {
    $response = Invoke-RestMethod -Uri $Url -TimeoutSec 10
    if ($response.status -ne 'ok') { throw 'Backend reported unhealthy status' }
    Write-Output 'Backend health: OK'
    & docker ps --filter name=mango- --format '{{.Names}}: {{.Status}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker status unavailable' }
    exit 0
} catch {
    Write-Error ('Local health check failed: ' + $_.Exception.Message)
    exit 1
}
