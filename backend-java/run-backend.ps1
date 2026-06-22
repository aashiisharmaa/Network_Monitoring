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
    $env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'update'
}

$buildDir = 'run-classes'
if (Test-Path $buildDir) {
    Remove-Item -Recurse -Force $buildDir
}
New-Item -ItemType Directory -Path $buildDir | Out-Null

$projectM2Repo = Join-Path $root '.m2\repository'
if (Test-Path $projectM2Repo) {
    $m2Repo = $projectM2Repo
} else {
    $m2Repo = Join-Path $env:USERPROFILE '.m2\repository'
}

if (-not (Test-Path $m2Repo)) {
    throw "Maven local repository not found: $m2Repo"
}

$jarPaths = Get-ChildItem $m2Repo -Recurse -Filter *.jar |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
    Where-Object {
        $_.FullName -notmatch '\\org\\apache\\maven\\' -and
        $_.FullName -notmatch '\\org\\apache\\maven\\plugins\\' -and
        $_.FullName -notmatch '\\org\\apache\\maven\\shared\\' -and
        $_.FullName -notmatch '\\org\\apache\\maven\\resolver\\' -and
        $_.FullName -notmatch '\\org\\codehaus\\plexus\\' -and
        $_.FullName -notmatch '\\org\\sonatype\\' -and
        $_.FullName -notmatch '\\org\\eclipse\\aether\\' -and
        $_.FullName -notmatch '\\org\\slf4j\\slf4j-api\\1\.7\.'
    } |
    ForEach-Object { $_.FullName }

$compileClasspath = $jarPaths -join ';'
$runtimeClasspath = (@('src\main\resources', $buildDir) + $jarPaths) -join ';'

$compileArgsFile = 'compile-backend.args'
@"
-cp
$compileClasspath
-d
"$buildDir"
"@ | Set-Content -Encoding ASCII $compileArgsFile

Get-ChildItem 'src\main\java' -Recurse -Filter *.java |
    ForEach-Object { $_.FullName.Substring($root.Length + 1) } |
    Add-Content -Encoding ASCII $compileArgsFile

& javac "@$compileArgsFile"

$runArgsFile = 'run-backend.args'
@"
-cp
$runtimeClasspath
com.network.monitoring.Application
"@ | Set-Content -Encoding ASCII $runArgsFile

& java "@$runArgsFile"
