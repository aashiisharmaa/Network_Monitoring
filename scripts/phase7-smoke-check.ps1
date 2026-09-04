param(
    [string]$BackendUrl = "http://127.0.0.1:3001",
    [string]$FrontendUrl = "http://127.0.0.1:5173"
)

$ErrorActionPreference = "Stop"

function Test-JsonEndpoint([string]$Url, [string]$Name) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 $Url
        $payload = $response.Content | ConvertFrom-Json
        if ($response.StatusCode -ne 200 -or $payload.success -ne $true) {
            throw "unexpected response"
        }
        Write-Host "PASS $Name ($($response.StatusCode))" -ForegroundColor Green
        return $payload
    } catch {
        Write-Host "FAIL $Name - $($_.Exception.Message)" -ForegroundColor Red
        throw
    }
}

try {
    Test-JsonEndpoint "$BackendUrl/health" "backend health" | Out-Null
    $dashboard = Test-JsonEndpoint "$BackendUrl/api/v1/dashboard/stats" "dashboard API"
    if ($null -eq $dashboard.data) {
        throw "dashboard response did not contain data"
    }

    $frontend = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 $FrontendUrl
    if ($frontend.StatusCode -ne 200) {
        throw "frontend returned HTTP $($frontend.StatusCode)"
    }
    Write-Host "PASS frontend entry page ($($frontend.StatusCode))" -ForegroundColor Green
    Write-Host "Smoke check passed." -ForegroundColor Green
} catch {
    Write-Host "Smoke check failed." -ForegroundColor Red
    exit 1
}
