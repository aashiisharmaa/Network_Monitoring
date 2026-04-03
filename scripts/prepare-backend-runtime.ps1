$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$backendPath = Join-Path $projectRoot "backend"
$runtimeRoot = Join-Path $projectRoot "frontend\electron\runtime"
$runtimeBackend = Join-Path $runtimeRoot "backend"
$runtimeZip = Join-Path $runtimeRoot "backend-runtime.zip"

if (Test-Path $runtimeBackend) {
  Remove-Item $runtimeBackend -Recurse -Force
}
if (Test-Path $runtimeZip) {
  Remove-Item $runtimeZip -Force
}

New-Item -ItemType Directory -Path $runtimeBackend -Force | Out-Null

$itemsToCopy = @(
  "src",
  "prisma",
  "node_modules",
  "package.json",
  "package-lock.json",
  "tsconfig.json",
  "prisma.config.ts"
)

foreach ($item in $itemsToCopy) {
  $source = Join-Path $backendPath $item
  if (-not (Test-Path $source)) {
    throw "Missing backend runtime item: $source"
  }

  $destination = Join-Path $runtimeBackend $item
  if (Test-Path $source -PathType Container) {
    Copy-Item $source $destination -Recurse -Force
  } else {
    Copy-Item $source $destination -Force
  }
}

Compress-Archive -Path (Join-Path $runtimeBackend "*") -DestinationPath $runtimeZip -CompressionLevel Optimal -Force

Write-Host "Prepared backend runtime at: $runtimeBackend" -ForegroundColor Green
Write-Host "Prepared backend runtime zip: $runtimeZip" -ForegroundColor Green
