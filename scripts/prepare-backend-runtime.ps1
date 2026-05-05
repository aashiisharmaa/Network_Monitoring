$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$backendJavaPath = Join-Path $projectRoot "backend-java"
$runtimeRoot = Join-Path $projectRoot "frontend\electron\runtime"
$runtimeBackend = Join-Path $runtimeRoot "backend"
$runtimeClasses = Join-Path $runtimeBackend "classes"
$runtimeResources = Join-Path $runtimeBackend "resources"
$runtimeLib = Join-Path $runtimeBackend "lib"

if (Test-Path $runtimeBackend) {
  Remove-Item $runtimeBackend -Recurse -Force
}

New-Item -ItemType Directory -Path $runtimeClasses -Force | Out-Null
New-Item -ItemType Directory -Path $runtimeResources -Force | Out-Null
New-Item -ItemType Directory -Path $runtimeLib -Force | Out-Null

$projectM2Repo = Join-Path $backendJavaPath ".m2\repository"
if (Test-Path $projectM2Repo) {
  $m2Repo = $projectM2Repo
} else {
  $m2Repo = Join-Path $env:USERPROFILE ".m2\repository"
}

if (-not (Test-Path $m2Repo)) {
  throw "Maven local repository not found: $m2Repo"
}

Write-Host "Building Java backend runtime from source..." -ForegroundColor Yellow

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
  Group-Object Name |
  ForEach-Object { $_.Group | Select-Object -First 1 } |
  Sort-Object FullName

if (-not $jarPaths) {
  throw "No dependency jars were found in $m2Repo"
}

$null = Push-Location $backendJavaPath
try {
foreach ($jar in $jarPaths) {
  Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $runtimeLib $jar.Name) -Force
}

$runtimeClasspathJars = Get-ChildItem $runtimeLib -Filter *.jar | Sort-Object Name
if (-not $runtimeClasspathJars) {
  throw "No runtime dependency jars were copied to $runtimeLib"
}

$compileClasspath = ($runtimeClasspathJars.FullName -join ';')
$compileClasspath = $compileClasspath -replace '\\', '/'

$compileArgsFile = Join-Path $runtimeBackend "compile-backend.args"
@"
-cp
"$compileClasspath"
-d
"$(($runtimeClasses -replace '\\', '/'))"
"@ | Set-Content -Encoding ASCII $compileArgsFile

Get-ChildItem 'src\main\java' -Recurse -Filter *.java |
  ForEach-Object { $_.FullName.Substring($backendJavaPath.Length + 1) -replace '\\', '/' } |
  Add-Content -Encoding ASCII $compileArgsFile

& javac "@$compileArgsFile"
if ($LASTEXITCODE -ne 0) {
  throw "Failed to compile Java backend sources."
}

$resourceSource = Join-Path $backendJavaPath 'src\main\resources'
if (Test-Path $resourceSource) {
  Copy-Item (Join-Path $resourceSource '*') $runtimeResources -Recurse -Force
}
}
finally {
  Pop-Location
}

Write-Host "Prepared backend runtime at: $runtimeBackend" -ForegroundColor Green
Write-Host "Compiled classes: $runtimeClasses" -ForegroundColor DarkGray
Write-Host "Copied resources: $runtimeResources" -ForegroundColor DarkGray
Write-Host "Copied dependency jars: $runtimeLib" -ForegroundColor DarkGray
