param(
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin',
    [switch]$FullSuite,
    [string]$BuildDirectory = 'target'
)

$ErrorActionPreference = 'Stop'
$backendPath = (Resolve-Path (Join-Path $PSScriptRoot '..\backend-java')).Path
$mavenPath = Join-Path $backendPath 'tools\apache-maven-3.9.15\bin\mvn.cmd'
$serverPath = Join-Path $MySqlBin 'mysqld.exe'
$clientPath = Join-Path $MySqlBin 'mysql.exe'
$adminPath = Join-Path $MySqlBin 'mysqladmin.exe'
foreach ($requiredPath in @($mavenPath, $serverPath, $clientPath, $adminPath)) {
    if (!(Test-Path -LiteralPath $requiredPath)) { throw "Required executable not found: $requiredPath" }
}
if (Get-NetTCPConnection -State Listen -LocalPort 13316 -ErrorAction SilentlyContinue) {
    throw 'Disposable MySQL port 13316 is already occupied; no existing server will be reused or stopped.'
}
$testDataPath = Join-Path $backendPath ('target\alarm-config-mysql-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDataPath -Force | Out-Null
$previousAlarmUrl = $env:ALARM_CONFIG_TEST_DB_URL
$previousKpiUrl = $env:KPI_TEST_DB_URL
$serverProcess = $null
$testExitCode = 1
# On Windows, mysqld's monitor process can spawn the actual listening worker.
function Test-OwnedAlarmListener([int]$CandidateId) {
    if (!$serverProcess) { return $false }
    if ($CandidateId -eq $serverProcess.Id) { return $true }
    $candidate = Get-CimInstance Win32_Process -Filter "ProcessId=$CandidateId" -ErrorAction SilentlyContinue
    return $candidate -and $candidate.ParentProcessId -eq $serverProcess.Id `
        -and $candidate.ExecutablePath -eq $serverPath -and $candidate.CommandLine.Contains($testDataPath)
}
Push-Location $backendPath
try {
    # MySQL writes ordinary initialization notices to stderr. Start-Process keeps
    # PowerShell 5's NativeCommandError handling from treating these as a failure.
    $initializer = Start-Process -FilePath $serverPath -ArgumentList @(
        '--no-defaults', '--initialize-insecure', ('--datadir="' + $testDataPath + '"'), '--console'
    ) -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput ($testDataPath + '.initialize.out.log') `
        -RedirectStandardError ($testDataPath + '.initialize.err.log')
    if ($initializer.ExitCode -ne 0) { throw 'Disposable MySQL initialization failed; inspect retained initialization logs.' }
    $serverProcess = Start-Process -FilePath $serverPath -ArgumentList @(
        '--no-defaults', ('--datadir="' + $testDataPath + '"'), '--port=13316', '--bind-address=127.0.0.1',
        '--mysqlx=OFF', '--innodb-buffer-pool-size=32M', '--console'
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testDataPath 'server.out.log') `
        -RedirectStandardError (Join-Path $testDataPath 'server.err.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        if ($serverProcess.HasExited) { throw 'Disposable MySQL exited during startup; inspect retained logs.' }
        $listener = Get-NetTCPConnection -State Listen -LocalPort 13316 -ErrorAction SilentlyContinue
        if ($listener) {
            if (!(Test-OwnedAlarmListener $listener.OwningProcess)) { throw 'Test port is owned by a different process.' }
            break
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    if (!$listener) { throw 'Disposable MySQL did not become ready within 30 seconds.' }
    & $clientPath '--no-defaults' '--protocol=TCP' '--host=127.0.0.1' '--port=13316' '--user=root' `
        '--execute=CREATE DATABASE alarm_config_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci'
    if ($LASTEXITCODE -ne 0) { throw 'Disposable database creation failed.' }
    $env:ALARM_CONFIG_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:13316/alarm_config_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
    # FullSuite must not inherit a separate database target for existing integration tests.
    Remove-Item Env:KPI_TEST_DB_URL -ErrorAction SilentlyContinue
    $buildArgument = '-Dbackend.build.directory=' + $BuildDirectory
    # On PowerShell 5, JVM warnings on stderr must not bypass Maven's exit code.
    $ErrorActionPreference = 'Continue'
    try {
        if ($FullSuite) { & $mavenPath $buildArgument 'test' '-q' 2>&1 | ForEach-Object { $_.ToString() } }
        else { & $mavenPath $buildArgument '-Dtest=AlarmTenantAccessTest,AlarmConfigurationServiceTest,AlarmConfigurationControllerTest,AlarmConfigurationMysqlTest,AlarmWorkflowMysqlTest' 'test' '-q' 2>&1 | ForEach-Object { $_.ToString() } }
        $testExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = 'Stop' }
} finally {
    $env:ALARM_CONFIG_TEST_DB_URL = $previousAlarmUrl
    $env:KPI_TEST_DB_URL = $previousKpiUrl
    if ($serverProcess -and !$serverProcess.HasExited) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort 13316 -ErrorAction SilentlyContinue
        if ($listener -and (Test-OwnedAlarmListener $listener.OwningProcess)) {
            & $adminPath '--no-defaults' '--protocol=TCP' '--host=127.0.0.1' '--port=13316' '--user=root' 'shutdown'
        }
        if (!$serverProcess.WaitForExit(10000)) { Stop-Process -Id $serverProcess.Id -Force }
    }
    Pop-Location
    Write-Host "Disposable MySQL logs/data retained at $testDataPath"
}
if ($testExitCode -ne 0) { throw "Alarm configuration tests failed (exit $testExitCode)." }
