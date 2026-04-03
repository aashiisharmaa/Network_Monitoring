$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$backendPath = Join-Path $projectRoot "backend"
$frontendPath = Join-Path $projectRoot "frontend"
$backendPidFile = Join-Path $projectRoot ".backend-dev.pid"

Write-Host "Stopping local stack..." -ForegroundColor Cyan

$stopped = $false

if (Test-Path $backendPidFile) {
  $pidValue = Get-Content $backendPidFile -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($pidValue) {
    try {
      Stop-Process -Id ([int]$pidValue) -Force -ErrorAction Stop
      Write-Host "Stopped backend process (PID: $pidValue)." -ForegroundColor Green
      $stopped = $true
    } catch {
      Write-Host "Could not stop backend PID $pidValue: $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
  }

  Remove-Item $backendPidFile -Force -ErrorAction SilentlyContinue
}

$procCandidates = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
  ($_.CommandLine -like "*$backendPath*src*server.ts*") -or
  ($_.CommandLine -like "*$backendPath*tsx*") -or
  ($_.CommandLine -like "*$frontendPath*vite*")
}

foreach ($proc in $procCandidates) {
  try {
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
    Write-Host "Stopped process $($proc.ProcessId)." -ForegroundColor DarkGray
    $stopped = $true
  } catch {
    Write-Host "Could not stop process $($proc.ProcessId): $($_.Exception.Message)" -ForegroundColor DarkYellow
  }
}

if (-not $stopped) {
  Write-Host "No matching frontend/backend dev processes were found." -ForegroundColor DarkGray
}
