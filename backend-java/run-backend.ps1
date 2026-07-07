$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$null = Push-Location $root

if ([string]::IsNullOrWhiteSpace($env:DATABASE_URL)) {
    $env:DATABASE_URL = 'mysql://nm_app_user:NmApp1234@127.0.0.1:3306/kpi_tool_nm'
}

if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    $env:JWT_SECRET = 'local-desktop-jwt-secret-change-me'
}

if ([string]::IsNullOrWhiteSpace($env:CORS_ORIGINS)) {
    $env:CORS_ORIGINS = 'http://localhost:5173,http://127.0.0.1:5173,http://localhost:4173,http://127.0.0.1:4173,file://'
}

if ([string]::IsNullOrWhiteSpace($env:SPRING_JPA_HIBERNATE_DDL_AUTO)) {
    $env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'none'
}

$maven = Join-Path $root 'tools\apache-maven-3.9.15\bin\mvn.cmd'
if (-not (Test-Path $maven)) {
    throw "Maven executable not found: $maven"
}

& $maven -q "-Dmaven.repo.local=.m2" -DskipTests package
if ($LASTEXITCODE -ne 0) {
    throw "Backend package failed with exit code $LASTEXITCODE"
}

$jar = Join-Path $root 'target\network-monitoring-backend-1.0.0.jar'
if (-not (Test-Path $jar)) {
    throw "Backend jar not found: $jar"
}

& java -jar $jar
