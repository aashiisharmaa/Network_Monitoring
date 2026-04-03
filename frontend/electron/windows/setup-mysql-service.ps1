param(
  [string]$RootPassword = "",
  [int]$MySqlPort = 3306
)

$ErrorActionPreference = "Stop"
$AppDbName = "kpi_tool_nm"
$AppDbUser = "nm_app_user"
$AppDbPassword = "NmApp1234"

function Assert-Admin {
  $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
  $principal = New-Object Security.Principal.WindowsPrincipal($currentIdentity)
  if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Please run this script as Administrator."
  }
}

function Try-InstallMySql {
  if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    return
  }

  $candidates = @(
    "Oracle.MySQL",
    "MySQL.MySQLServer"
  )

  foreach ($id in $candidates) {
    Write-Host "Trying winget package: $id" -ForegroundColor Yellow
    winget install -e --id $id --accept-package-agreements --accept-source-agreements --silent
    if ($LASTEXITCODE -eq 0) {
      Write-Host "Installed MySQL package: $id" -ForegroundColor Green
      return
    }
  }
}

function Find-MySqlExe {
  $candidates = @(
    "$env:ProgramFiles\MySQL\MySQL Server 8.0\bin\mysql.exe",
    "$env:ProgramFiles\MySQL\MySQL Server 8.4\bin\mysql.exe",
    "$env:ProgramFiles\MySQL\MySQL Server 9.0\bin\mysql.exe",
    "$env:ProgramFiles\MariaDB 11.0\bin\mysql.exe"
  )

  foreach ($candidate in $candidates) {
    if (Test-Path $candidate) {
      return $candidate
    }
  }

  $fromPath = Get-Command mysql -ErrorAction SilentlyContinue
  if ($fromPath) {
    return $fromPath.Source
  }

  return $null
}

function Start-MySqlService {
  $serviceNames = @("MySQL80", "MySQL", "MariaDB")
  foreach ($name in $serviceNames) {
    $service = Get-Service -Name $name -ErrorAction SilentlyContinue
    if ($service) {
      if ($service.Status -ne "Running") {
        Start-Service -Name $name
      }
      Write-Host "MySQL service running: $name" -ForegroundColor Green
      return $true
    }
  }

  return $false
}

Assert-Admin

Write-Host "Checking MySQL installation..." -ForegroundColor Cyan
$mysqlExe = Find-MySqlExe
if (-not $mysqlExe) {
  Write-Host "MySQL not found. Attempting installation via winget..." -ForegroundColor Yellow
  Try-InstallMySql
  $mysqlExe = Find-MySqlExe
}

if (-not $mysqlExe) {
  throw "MySQL executable not found. Install MySQL Server, then rerun this script."
}

if (-not (Start-MySqlService)) {
  throw "MySQL service not found. Ensure MySQL service is installed (e.g. MySQL80)."
}

Write-Host "Bootstrapping local database/user..." -ForegroundColor Cyan

$effectiveRootPassword = $null
$passwordCandidates = @()
if ($RootPassword -ne "") {
  $passwordCandidates += $RootPassword
} else {
  # Common local defaults: empty root password or "root".
  $passwordCandidates += ""
  $passwordCandidates += "root"
}

foreach ($candidate in $passwordCandidates) {
  $authArgs = @()
  if ($candidate -ne "") {
    $authArgs += "-p$candidate"
  }

  & $mysqlExe -h 127.0.0.1 -P $MySqlPort -u root @authArgs -e "SELECT 1;" | Out-Null
  if ($LASTEXITCODE -eq 0) {
    $effectiveRootPassword = $candidate
    break
  }
}

if ($null -eq $effectiveRootPassword) {
  throw "Unable to authenticate as root. Rerun with -RootPassword '<your_root_password>'."
}

$authArgs = @()
if ($effectiveRootPassword -ne "") {
  $authArgs += "-p$effectiveRootPassword"
}

$sql = @"
CREATE DATABASE IF NOT EXISTS $AppDbName;
DROP USER IF EXISTS '$AppDbUser'@'localhost';
DROP USER IF EXISTS '$AppDbUser'@'127.0.0.1';
CREATE USER '$AppDbUser'@'localhost' IDENTIFIED WITH mysql_native_password BY '$AppDbPassword';
CREATE USER '$AppDbUser'@'127.0.0.1' IDENTIFIED WITH mysql_native_password BY '$AppDbPassword';
GRANT ALL PRIVILEGES ON $AppDbName.* TO '$AppDbUser'@'localhost';
GRANT ALL PRIVILEGES ON $AppDbName.* TO '$AppDbUser'@'127.0.0.1';
FLUSH PRIVILEGES;
"@

& $mysqlExe -h 127.0.0.1 -P $MySqlPort -u root @authArgs -e $sql
if ($LASTEXITCODE -ne 0) {
  throw "Failed to create database/user."
}

Write-Host "MySQL setup completed." -ForegroundColor Green
Write-Host "Database: $AppDbName" -ForegroundColor DarkGray
Write-Host "User: $AppDbUser" -ForegroundColor DarkGray
Write-Host "Password: $AppDbPassword" -ForegroundColor DarkGray
