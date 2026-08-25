$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$null = Push-Location $root

$javaHomeCandidates = @()
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaHomeCandidates += $env:JAVA_HOME
}
$javaHomeCandidates += Get-ChildItem 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'jdk-17*' } |
    Sort-Object LastWriteTime -Descending |
    ForEach-Object { $_.FullName }

$javaHome = $javaHomeCandidates | Where-Object {
    Test-Path (Join-Path $_ 'bin\java.exe')
} | Select-Object -First 1

if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw "JDK 17 was not found. Install JDK 17 or set JAVA_HOME to a JDK 17 directory."
}

$env:JAVA_HOME = $javaHome
$env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
$java = Join-Path $javaHome 'bin\java.exe'

if ([string]::IsNullOrWhiteSpace($env:PORT)) {
    $env:PORT = '3001'
}

if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_URL)) {
    $env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:3306/kpi_tool?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true'
}

if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_USERNAME)) {
    $env:SPRING_DATASOURCE_USERNAME = 'nm_app_user'
}

if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_PASSWORD)) {
    $env:SPRING_DATASOURCE_PASSWORD = 'NmApp1234'
}

if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    $env:JWT_SECRET = 'local-desktop-jwt-secret-change-me'
}

if ([string]::IsNullOrWhiteSpace($env:CORS_ORIGINS)) {
    $env:CORS_ORIGINS = 'http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174,http://localhost:4173,http://127.0.0.1:4173,file://'
}

if ([string]::IsNullOrWhiteSpace($env:SPRING_JPA_HIBERNATE_DDL_AUTO)) {
    $env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'update'
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

& $java -jar $jar
