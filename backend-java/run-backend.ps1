$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$null = Push-Location $root

function Import-EnvFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path $Path)) {
        return
    }

    $loadableKeys = @(
        'JWT_SECRET',
        'LICENSE_DEVICE_ID',
        'LICENSE_FILE_PATH',
        'LICENSE_SIGNING_SECRET',
        'PORT',
        'SPRING_DATASOURCE_URL',
        'SPRING_DATASOURCE_USERNAME',
        'SPRING_DATASOURCE_PASSWORD',
        'SPRING_JPA_HIBERNATE_DDL_AUTO',
        'CORS_ORIGINS'
    )

    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
            return
        }

        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            return
        }

        $name = $matches[1]
        if ($name -notin $loadableKeys) {
            return
        }

        $value = $matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            if ($value.Length -ge 2) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }

        if ([string]::IsNullOrWhiteSpace((Get-Item -Path "Env:$name" -ErrorAction SilentlyContinue).Value)) {
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

$projectRoot = Split-Path $root -Parent
Import-EnvFile -Path (Join-Path $projectRoot '.env')

$javaHomeCandidates = @()
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaHomeCandidates += $env:JAVA_HOME
}
$javaHomeCandidates += Get-ChildItem 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^jdk-(17|21)' } |
    Sort-Object LastWriteTime -Descending |
    ForEach-Object { $_.FullName }

$javaHome = $javaHomeCandidates | Where-Object {
    Test-Path (Join-Path $_ 'bin\java.exe')
} | Select-Object -First 1

if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw "JDK 17 or 21 was not found. Install a supported JDK or set JAVA_HOME."
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
