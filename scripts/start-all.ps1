param(
  [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendPath = Join-Path $projectRoot "frontend"
$backendJavaPath = Join-Path $projectRoot "backend-java"
$backendPidFile = Join-Path $projectRoot ".backend-java-dev.pid"

Write-Host "Starting local stack (Java backend + frontend)..." -ForegroundColor Cyan

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
  throw "npm is not installed or not available in PATH."
}

if (-not (Test-Path (Join-Path $frontendPath ".env")) -and (Test-Path (Join-Path $frontendPath ".env.example"))) {
  Copy-Item (Join-Path $frontendPath ".env.example") (Join-Path $frontendPath ".env")
  Write-Host "Created frontend/.env from .env.example" -ForegroundColor DarkGray
}

if (-not $SkipInstall) {
  Write-Host "Installing frontend dependencies..." -ForegroundColor Yellow
  npm install --prefix $frontendPath
  if ($LASTEXITCODE -ne 0) {
    throw "Frontend dependency install failed."
  }
}

if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_URL)) {
  $env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:3306/kpi_tool?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true"
}
if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_USERNAME)) {
  $env:SPRING_DATASOURCE_USERNAME = "nm_app_user"
}
if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_PASSWORD)) {
  $env:SPRING_DATASOURCE_PASSWORD = "NmApp1234"
}
if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
  $env:JWT_SECRET = "local-desktop-jwt-secret-change-me"
}
if ([string]::IsNullOrWhiteSpace($env:CORS_ORIGINS)) {
  $env:CORS_ORIGINS = "http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174,http://localhost:4173,http://127.0.0.1:4173,file://"
}
if ([string]::IsNullOrWhiteSpace($env:SPRING_JPA_HIBERNATE_DDL_AUTO)) {
  $env:SPRING_JPA_HIBERNATE_DDL_AUTO = "update"
}

$backendScript = Join-Path $backendJavaPath "run-backend.ps1"
if (-not (Test-Path $backendScript)) {
  throw "Java backend startup script not found: $backendScript"
}

Write-Host "Starting Java backend in a separate PowerShell window..." -ForegroundColor Yellow
$backendProcess = Start-Process -FilePath "powershell" `
  -ArgumentList "-NoProfile", "-NoExit", "-ExecutionPolicy", "Bypass", "-File", $backendScript `
  -WorkingDirectory $backendJavaPath `
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
