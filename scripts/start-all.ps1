param(
  [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendPath = Join-Path $projectRoot "frontend"
$backendPath = Join-Path $projectRoot "backend"
$backendPidFile = Join-Path $projectRoot ".backend-dev.pid"

Write-Host "Starting local stack (backend + frontend)..." -ForegroundColor Cyan

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
  throw "npm is not installed or not available in PATH."
}

if (-not (Test-Path (Join-Path $backendPath ".env")) -and (Test-Path (Join-Path $backendPath ".env.example"))) {
  Copy-Item (Join-Path $backendPath ".env.example") (Join-Path $backendPath ".env")
  Write-Host "Created backend/.env from .env.example" -ForegroundColor DarkGray
}

if (-not (Test-Path (Join-Path $frontendPath ".env")) -and (Test-Path (Join-Path $frontendPath ".env.example"))) {
  Copy-Item (Join-Path $frontendPath ".env.example") (Join-Path $frontendPath ".env")
  Write-Host "Created frontend/.env from .env.example" -ForegroundColor DarkGray
}

if (-not $SkipInstall) {
  Write-Host "Installing backend dependencies..." -ForegroundColor Yellow
  npm install --prefix $backendPath
  if ($LASTEXITCODE -ne 0) {
    throw "Backend dependency install failed."
  }

  Write-Host "Installing frontend dependencies..." -ForegroundColor Yellow
  npm install --prefix $frontendPath
  if ($LASTEXITCODE -ne 0) {
    throw "Frontend dependency install failed."
  }
}

Write-Host "Starting backend API in a separate PowerShell window..." -ForegroundColor Yellow
$backendCmd = "Set-Location -LiteralPath '$backendPath'; npm run dev"
$backendProcess = Start-Process -FilePath "powershell" `
  -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $backendCmd `
  -PassThru

Set-Content -Path $backendPidFile -Value $backendProcess.Id
Write-Host "Backend running (PID: $($backendProcess.Id))" -ForegroundColor Green
Write-Host "To stop backend quickly: scripts\stop-all.cmd" -ForegroundColor DarkGray

Write-Host "Launching frontend dev server..." -ForegroundColor Yellow
Write-Host "Tip: keep this terminal open while using the app." -ForegroundColor DarkGray
npm run dev --prefix $frontendPath
if ($LASTEXITCODE -ne 0) {
  throw "Failed to launch frontend dev server."
}
