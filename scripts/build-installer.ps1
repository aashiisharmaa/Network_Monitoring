$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
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

Write-Host "Building desktop installer..." -ForegroundColor Cyan

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
  throw "npm is not installed or not available in PATH."
}

Write-Host "Stopping lingering backend dev processes (if any)..." -ForegroundColor Yellow
if (Test-Path $backendPidFile) {
  $pidValue = Get-Content $backendPidFile -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($pidValue) {
    try {
      Stop-ProcessTree -ProcessId ([int]$pidValue)
      Write-Host "Stopped backend process tree from pid file: $pidValue" -ForegroundColor DarkGray
    } catch {
      Write-Host "Could not stop backend pid ${pidValue}: $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
  }
  Remove-Item $backendPidFile -Force -ErrorAction SilentlyContinue
}

$backendProcCandidates = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
  ($_.CommandLine -like "*run-backend.ps1*") -or
  ($_.CommandLine -like "*backend-java*backend.jar*") -or
  ($_.CommandLine -like "*backend-java*spring-boot:run*") -or
  ($_.CommandLine -like "*backend*src*server.ts*") -or
  ($_.CommandLine -like "*network monitoring*backend*tsx*")
}

foreach ($proc in $backendProcCandidates) {
  try {
    Stop-ProcessTree -ProcessId $proc.ProcessId
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

Write-Host "Running Electron build..." -ForegroundColor Yellow
$env:CSC_IDENTITY_AUTO_DISCOVERY = "false"
$env:WIN_CSC_LINK = ""
$env:WIN_CSC_KEY_PASSWORD = ""
$env:ELECTRON_BUILDER_ALLOW_UNRESOLVED_DEPENDENCIES = "true"

npm run desktop:build --prefix $frontendPath
if ($LASTEXITCODE -ne 0) {
  throw "Installer build failed."
}

$packedRuntimeRoot = Join-Path $frontendPath "release\win-unpacked\resources\backend"
if (-not (Test-Path (Join-Path $packedRuntimeRoot "classes")) -or -not (Test-Path (Join-Path $packedRuntimeRoot "lib"))) {
  throw "Installer built, but packaged backend runtime is missing: $packedRuntimeRoot"
}

$releasePath = Join-Path $frontendPath "release"
Write-Host "Installer build complete." -ForegroundColor Green
Write-Host "Output folder: $releasePath" -ForegroundColor DarkGray
