$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendPath = Join-Path $projectRoot "frontend"
$backendPath = Join-Path $projectRoot "backend"

Write-Host "Building desktop installer..." -ForegroundColor Cyan

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
  throw "npm is not installed or not available in PATH."
}

Write-Host "Stopping lingering backend dev processes (if any)..." -ForegroundColor Yellow
$backendProcCandidates = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
  ($_.CommandLine -like "*backend*src*server.ts*") -or
  ($_.CommandLine -like "*network monitoring*backend*tsx*")
}

foreach ($proc in $backendProcCandidates) {
  try {
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
    Write-Host "Stopped process $($proc.ProcessId)" -ForegroundColor DarkGray
  } catch {
    Write-Host "Could not stop process $($proc.ProcessId): $($_.Exception.Message)" -ForegroundColor DarkYellow
  }
}

Write-Host "Installing frontend dependencies..." -ForegroundColor Yellow
npm install --prefix $frontendPath
if ($LASTEXITCODE -ne 0) {
  throw "Frontend dependency install failed."
}

Write-Host "Installing backend dependencies..." -ForegroundColor Yellow
npm install --include=dev --prefix $backendPath
if ($LASTEXITCODE -ne 0) {
  throw "Backend dependency install failed."
}

Write-Host "Generating Prisma client (backend)..." -ForegroundColor Yellow
$generateSucceeded = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
  npm run generate --prefix $backendPath
  if ($LASTEXITCODE -eq 0) {
    $generateSucceeded = $true
    break
  }

  Write-Host "Prisma generate failed (attempt $attempt/3). Retrying..." -ForegroundColor DarkYellow
  $prismaTmpPattern = Join-Path $backendPath "node_modules\.prisma\client\query_engine-windows.dll.node.tmp*"
  Remove-Item $prismaTmpPattern -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
}

if (-not $generateSucceeded) {
  throw "Backend Prisma generation failed after retries. Close any running app/terminal using backend and try again."
}

Write-Host "Preparing packaged backend runtime..." -ForegroundColor Yellow
powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "prepare-backend-runtime.ps1")
if ($LASTEXITCODE -ne 0) {
  throw "Failed to prepare packaged backend runtime."
}

Write-Host "Running Electron build..." -ForegroundColor Yellow
$env:CSC_IDENTITY_AUTO_DISCOVERY = "false"
$env:WIN_CSC_LINK = ""
$env:WIN_CSC_KEY_PASSWORD = ""
$env:ELECTRON_BUILDER_ALLOW_UNRESOLVED_DEPENDENCIES = "true"

npm run desktop:build --prefix $frontendPath
if ($LASTEXITCODE -ne 0) {
  throw "Installer build failed."
}

$packedRuntimeZip = Join-Path $frontendPath "release\win-unpacked\resources\backend-runtime.zip"
if (-not (Test-Path $packedRuntimeZip)) {
  throw "Installer built, but packaged backend runtime zip is missing: $packedRuntimeZip"
}

$releasePath = Join-Path $frontendPath "release"
Write-Host "Installer build complete." -ForegroundColor Green
Write-Host "Output folder: $releasePath" -ForegroundColor DarkGray
