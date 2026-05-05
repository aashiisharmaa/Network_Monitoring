$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$backendPath = Join-Path $projectRoot "backend"
$frontendPath = Join-Path $projectRoot "frontend"
$backendJavaPath = Join-Path $projectRoot "backend-java"
$backendPidFile = Join-Path $projectRoot ".backend-java-dev.pid"

function Stop-ProcessTree {
  param(
    [Parameter(Mandatory = $true)]
    [int]$ProcessId
  )

  & taskkill /PID $ProcessId /T /F | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Stop-Process -Id $ProcessId -Force -ErrorAction Stop
  }
}

Write-Host "Stopping local stack..." -ForegroundColor Cyan

$stopped = $false

if (Test-Path $backendPidFile) {
  $pidValue = Get-Content $backendPidFile -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($pidValue) {
    try {
      Stop-ProcessTree -ProcessId ([int]$pidValue)
      Write-Host "Stopped backend process tree (PID: $pidValue)." -ForegroundColor Green
      $stopped = $true
    } catch {
      Write-Host "Could not stop backend PID ${pidValue}: $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
  }

  Remove-Item $backendPidFile -Force -ErrorAction SilentlyContinue
}

$procCandidates = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
  ($_.CommandLine -like "*run-backend.ps1*") -or
  ($_.CommandLine -like "*$backendJavaPath*backend.jar*") -or
  ($_.CommandLine -like "*$backendJavaPath*spring-boot:run*") -or
  ($_.CommandLine -like "*$backendPath*src*server.ts*") -or
  ($_.CommandLine -like "*$backendPath*tsx*") -or
  ($_.CommandLine -like "*$frontendPath*vite*")
}

foreach ($proc in $procCandidates) {
  try {
    Stop-ProcessTree -ProcessId $proc.ProcessId
    Write-Host "Stopped process $($proc.ProcessId)." -ForegroundColor DarkGray
    $stopped = $true
  } catch {
    Write-Host "Could not stop process $($proc.ProcessId): $($_.Exception.Message)" -ForegroundColor DarkYellow
  }
}

if (-not $stopped) {
  Write-Host "No matching frontend/backend dev processes were found." -ForegroundColor DarkGray
}
