param(
    [string]$PythonExecutable = 'python',
    [string]$OutputDirectory = 'reports/performance/full-test-2026-11-19',
    [switch]$SkipBackend,
    [switch]$SkipBrowser,
    [switch]$LiveMap
)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $workspace
$results = [System.Collections.Generic.List[object]]::new()
$environmentNames = @('KPI_RCA_PYTHON','KPI_REPORT_PREVIEW_DIR','ALARM_REPORT_PREVIEW_DIR','KPI_BENCHMARK_CACHE_FILE','KPI_RCA_EXPORT_BENCHMARK_CONFIG')
$previous = @{}
foreach ($name in $environmentNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $output = (Resolve-Path $OutputDirectory).Path
    function Invoke-Check([string]$Name, [string]$Executable, [string[]]$Arguments) {
        $started = [DateTime]::UtcNow
        $logPath = Join-Path $output ($Name + '.log')
        $ErrorActionPreference = 'Continue'
        if (!(Get-Command $Executable -ErrorAction SilentlyContinue)) {
            "Required executable not found: $Executable" | Set-Content -LiteralPath $logPath
            $code = 127
        } else {
            $LASTEXITCODE = 0
            & $Executable @Arguments *> $logPath
            $code = $LASTEXITCODE
            # PowerShell 5 marks normal native stderr (e.g. unittest progress)
            # as an error stream even when the process exits successfully.
        }
        $results.Add([pscustomobject]@{ check=$Name; exitCode=$code; seconds=([DateTime]::UtcNow-$started).TotalSeconds; log=$logPath })
        $results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'checks.json') -Encoding UTF8
        Write-Host "$Name : exit $code ($logPath)"
    }
    $data = Join-Path $output 'test-data'
    Invoke-Check 'generate-data' $PythonExecutable @('scripts/generate_project_test_data.py','--output',$data)
    if ($results[-1].exitCode -ne 0) { throw 'Synthetic data preparation failed.' }
    # Synthetic data remains independent of the application's upload storage.
    $env:KPI_RCA_PYTHON = (Get-Command $PythonExecutable -ErrorAction Stop).Source
    $samples = Join-Path $output 'samples'
    New-Item -ItemType Directory -Force $samples | Out-Null
    $env:KPI_REPORT_PREVIEW_DIR = $samples
    $env:ALARM_REPORT_PREVIEW_DIR = $samples
    $env:KPI_BENCHMARK_CACHE_FILE = Join-Path $data '500.json'
    $env:KPI_RCA_EXPORT_BENCHMARK_CONFIG = Join-Path $data 'rca-benchmark-config.json'
    if (!$SkipBackend) {
        Invoke-Check 'backend' 'powershell.exe' @('-NoProfile','-ExecutionPolicy','Bypass','-File','scripts/test_alarm_configuration.ps1','-ProjectSuite','-BuildDirectory','target/project-verification')
    }
    Invoke-Check 'frontend-unit' 'npm.cmd' @('--prefix','frontend','run','test:frontend')
    Invoke-Check 'frontend-lint' 'npm.cmd' @('--prefix','frontend','run','lint')
    Invoke-Check 'frontend-build' 'npm.cmd' @('--prefix','frontend','run','build')
    Invoke-Check 'api-contracts' $PythonExecutable @('scripts/verify_backend_api_contract.py')
    Invoke-Check 'python-unit' $PythonExecutable @('-m','unittest','scripts.test_rca_mean','scripts.test_rca_export','scripts.test_benchmark_backend')
    Invoke-Check 'chart-aggregation' 'node.exe' @('scripts/benchmark_frontend_analytics.mjs')
    if (!$SkipBrowser) {
        # Browser scripts intercept backend APIs. Existing local Vite dev/preview servers must be running on 5173/4173.
        foreach ($name in @('test_portal_design_browser','test_alarm_summaries_browser','test_analytics_browser','test_frontend_smooth_browser','test_map_browser','test_map_loading_browser','test_rca_export_browser','test_report_portal_browser')) {
            Invoke-Check $name $PythonExecutable @("scripts/$name.py")
        }
        if ($LiveMap) { Invoke-Check 'live-google-map' $PythonExecutable @('scripts/test_map_startup_production.py') }
    }
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
$failed = @($results | Where-Object { $_.exitCode -ne 0 })
if ($failed.Count) { throw "$($failed.Count) checks failed; see checks.json and logs." }
Write-Host 'All selected checks passed. Skipped backend cases must still be reviewed in Surefire XML.'
