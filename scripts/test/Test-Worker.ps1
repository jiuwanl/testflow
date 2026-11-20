<#
.SYNOPSIS
验证Worker数据库版本拒绝、最小权限和非Web心跳。
.DESCRIPTION
需要已构建JAR及独立测试库；临时创建、清理测试迁移夹具。
.NOTES
会启动和结束自有Worker进程，结果：.local/verification/worker；不得与其他测试并行。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
$ErrorActionPreference = 'Stop'
Set-TestFlowEnvironment -Role Test
$config = Get-TestFlowConfig
$env:TF_DB_USERNAME = 'tf_test_worker'
$env:TF_DB_PASSWORD = $config.TestWorkerPassword
Remove-Item Env:TF_MIGRATION_DB_USERNAME -ErrorAction SilentlyContinue
Remove-Item Env:TF_MIGRATION_DB_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:TF_BOOTSTRAP_ADMIN_USERNAME -ErrorAction SilentlyContinue
Remove-Item Env:TF_BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
Grant-TestFlowWorker -TestDatabase
$jar = Join-Path $script:TestFlowRoot 'testflow-worker/target/testflow-worker-0.1.0-SNAPSHOT.jar'
$evidence = Join-Path $script:TestFlowRoot '.local/verification/worker'
$null = New-Item -ItemType Directory -Path $evidence -Force
$results = [System.Collections.Generic.List[string]]::new()
$java = (Get-Command java).Source

function Start-WorkerProbe {
    param([string]$Name)
    foreach ($key in @('TF_LOCAL_MYSQL_ROOT_PASSWORD','TF_LOCAL_REDIS_PASSWORD','TF_MIGRATION_DB_USERNAME','TF_MIGRATION_DB_PASSWORD',
        'TF_BOOTSTRAP_ADMIN_USERNAME','TF_BOOTSTRAP_ADMIN_PASSWORD','TF_REDIS_PASSWORD','TF_TEST_WORKER_PASSWORD')) {
        Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
    }
    $outFile = Join-Path $evidence "$Name.out.log"
    $errFile = Join-Path $evidence "$Name.err.log"
    $process = Start-Process -FilePath $java -ArgumentList @('-Xms128m','-Xmx512m','-jar',('"' + $jar + '"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput $outFile -RedirectStandardError $errFile
    return @{ Process=$process; Out=$outFile; Err=$errFile }
}
function Stop-WorkerProbe {
    param($Probe)
    $Probe.Process.Refresh()
    if (-not $Probe.Process.HasExited) {
        Stop-Process -InputObject $Probe.Process -ErrorAction Stop
        $Probe.Process.WaitForExit()
    }
}
function Assert-Rejected {
    param([string]$Name)
    $probe = Start-WorkerProbe -Name $Name
    try {
        if (-not $probe.Process.WaitForExit(50000)) { throw "$Name worker did not fail closed." }
        if ($probe.Process.ExitCode -eq 0) { throw "$Name unexpectedly succeeded." }
        $text = [System.IO.File]::ReadAllText($probe.Out)
        if ($text.Contains('Worker ready')) { throw "$Name registered before schema verification." }
        $results.Add($Name)
        Write-Host "PASS: $Name refused startup"
    } finally { Stop-WorkerProbe $probe }
}

$entries = & jar tf $jar
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Worker artifact.' }
$forbidden = @($entries | Where-Object { $_ -match 'BOOT-INF/lib/(flyway-|spring-session-|spring-webmvc-|spring-boot-(?:starter-web|webmvc|flyway|tomcat|session))' })
if ($forbidden.Count -ne 0) { throw 'Worker artifact contains forbidden Web/Session/Flyway runtime dependencies.' }
$results.Add('runtime-dependency-isolation')

$beforeIds = @(Invoke-TestFlowSql -Sql 'SELECT worker_id FROM testflow_test.tf_worker_node;')
$positive = Start-WorkerProbe -Name 'compatible'
try {
    $ready = $false
    $workerId = ''
    for ($i=0; $i -lt 60; $i++) {
        $positive.Process.Refresh()
        if ($positive.Process.HasExited) { throw 'Compatible Worker failed to start; inspect local probe logs.' }
        $currentIds = @(Invoke-TestFlowSql -Sql 'SELECT worker_id FROM testflow_test.tf_worker_node;')
        $newIds = @($currentIds | Where-Object { $_ -match '^[0-9a-f-]{36}$' -and $_ -notin $beforeIds })
        if ($newIds.Count -eq 1) { $workerId=$newIds[0]; $ready=$true; break }
        Start-Sleep -Milliseconds 400
    }
    if (-not $ready) { throw 'Compatible Worker did not become ready.' }
    Start-Sleep -Seconds 6
    $heartbeat = @(Invoke-TestFlowSql -Sql "SELECT COUNT(*) FROM testflow_test.tf_worker_node WHERE worker_id='$workerId' AND last_seen_at>started_at;")
    if ([int]$heartbeat[-1] -lt 1) { throw 'No real Worker heartbeat observed.' }
    $listeners = @(Get-NetTCPConnection -OwningProcess $positive.Process.Id -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -ne 0) { throw 'Non-Web Worker unexpectedly listens on a TCP port.' }
    $results.Add('compatible-schema-heartbeat-no-http-listener')
    Write-Host 'PASS: compatible Worker heartbeat observed; no TCP listener'
} finally { Stop-WorkerProbe $positive }

# Fault injection is strictly limited to the dedicated test database, never testflow.
try {
    Invoke-TestFlowSql -Sql "UPDATE testflow_test.flyway_schema_history SET version='999' WHERE version='6' AND success=1;"
    Assert-Rejected -Name 'incompatible-version'
} finally {
    Invoke-TestFlowSql -Sql "UPDATE testflow_test.flyway_schema_history SET version='6' WHERE version='999' AND success=1;"
}

try {
    Invoke-TestFlowSql -Sql @"
INSERT INTO testflow_test.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
VALUES(999,'3','worker probe failure','SQL','test-only',NULL,'test-probe',0,0);
"@
    Assert-Rejected -Name 'failed-migration'
} finally {
    Invoke-TestFlowSql -Sql "DELETE FROM testflow_test.flyway_schema_history WHERE installed_rank=999 AND description='worker probe failure';"
}

Invoke-TestFlowSql -Sql "CREATE DATABASE IF NOT EXISTS testflow_worker_empty; GRANT SELECT ON testflow_worker_empty.* TO 'tf_test_worker'@'%';"
$originalUrl = $env:TF_DB_URL
try {
    $env:TF_DB_URL = $env:TF_DB_URL.Replace('/testflow_test?', '/testflow_worker_empty?')
    Assert-Rejected -Name 'uninitialized-schema'
    $tables = @(Invoke-TestFlowSql -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='testflow_worker_empty';")
    if ([int]$tables[-1] -ne 0) { throw 'Worker modified the uninitialized schema.' }
} finally { $env:TF_DB_URL = $originalUrl }

@{ Passed=$true; Cases=$results.ToArray(); CheckedAt=(Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding utf8
Write-Host 'All Worker artifact/schema probes passed. Execution reliability is checked by the execution, plan and quality suites.'

