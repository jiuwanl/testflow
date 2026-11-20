<#
.SYNOPSIS
内部公共函数：读取开发配置并设置分角色环境。
.DESCRIPTION
由开发/测试工具加载；公共SQL函数用于受控初始化与测试，不作为日常入口。
.NOTES
读取.local/dev-secrets.json；加载本文件不启动服务，调用SQL函数会访问本项目开发MySQL。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'ProjectPaths.ps1')

function Get-TestFlowConfig {
    $path = Join-Path $script:TestFlowRoot '.local/dev-secrets.json'
    if (-not (Test-Path -LiteralPath $path)) { throw 'Run scripts/dev/Initialize-Local.ps1 first.' }
    return Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
}

function Set-TestFlowDockerEnvironment {
    $config = Get-TestFlowConfig
    $env:TF_LOCAL_MYSQL_ROOT_PASSWORD = $config.RootPassword
    $env:TF_LOCAL_REDIS_PASSWORD = $config.RedisPassword
    $env:TF_LOCAL_MYSQL_PORT = [string]$config.MysqlPort
    $env:TF_LOCAL_REDIS_PORT = [string]$config.RedisPort
}

function Set-TestFlowEnvironment {
    param([ValidateSet('Api','Worker','Test')][string]$Role = 'Api')
    $config = Get-TestFlowConfig
    $database = if ($Role -eq 'Test') { 'testflow_test' } else { 'testflow' }
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:TF_QUALITY_ENABLED = if ($Role -eq 'Api') { 'true' } else { 'false' }
    $env:TF_KAFKA_BOOTSTRAP_SERVERS = '127.0.0.1:19092'
    $env:TF_QUALITY_TOPIC = if ($Role -eq 'Test') { 'testflow-test.run-finalized.v1' } else { 'testflow.run-finalized.v1' }
    $env:TF_QUALITY_GROUP_ID = if ($Role -eq 'Test') { 'testflow-test-quality-v1' } else { 'testflow-quality-v1' }
    $env:TF_DB_URL = "jdbc:mysql://127.0.0.1:$($config.MysqlPort)/${database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
    $env:TF_DB_USERNAME = if ($Role -eq 'Worker') { 'tf_worker' } elseif ($Role -eq 'Test') { 'tf_test_api' } else { 'tf_api' }
    $env:TF_DB_PASSWORD = if ($Role -eq 'Worker') { $config.WorkerPassword } elseif ($Role -eq 'Test') { $config.TestApiPassword } else { $config.ApiPassword }
    $env:TF_MIGRATION_DB_USERNAME = if ($Role -eq 'Test') { 'tf_test_migrator' } else { 'tf_migrator' }
    $env:TF_MIGRATION_DB_PASSWORD = if ($Role -eq 'Test') { $config.TestMigrationPassword } else { $config.MigrationPassword }
    $env:TF_REDIS_HOST = '127.0.0.1'
    $env:TF_REDIS_PORT = [string]$config.RedisPort
    $env:TF_REDIS_PASSWORD = $config.RedisPassword
    $env:TF_SECRET_KEY = $config.SecretKey
    if (-not $config.PSObject.Properties['SnapshotHmacKey']) { throw 'Run scripts/dev/Initialize-Local.ps1 to generate the separate snapshot HMAC key.' }
    $env:TF_SNAPSHOT_HMAC_KEY = $config.SnapshotHmacKey
    $env:TF_SESSION_NAMESPACE = if ($Role -eq 'Test') { 'testflow-test:session' } else { 'testflow:session' }
    $env:TF_LOGIN_RATE_PREFIX = if ($Role -eq 'Test') { 'testflow-test:login' } else { 'testflow:login' }
    $env:TF_BOOTSTRAP_ADMIN_USERNAME = if ($Role -eq 'Test') { 'test-admin' } else { 'local-admin' }
    $env:TF_BOOTSTRAP_ADMIN_PASSWORD = if ($Role -eq 'Test') { $config.TestAdminPassword } else { $config.AdminPassword }
    # A child application must not inherit the operator's Docker/root credentials.
    Remove-Item Env:TF_LOCAL_MYSQL_ROOT_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:TF_LOCAL_REDIS_PASSWORD -ErrorAction SilentlyContinue
    if ($Role -ne 'Test') { Remove-Item Env:TF_TEST_WORKER_PASSWORD -ErrorAction SilentlyContinue }
    if ($Role -eq 'Worker') {
        Remove-Item Env:TF_MIGRATION_DB_USERNAME -ErrorAction SilentlyContinue
        Remove-Item Env:TF_MIGRATION_DB_PASSWORD -ErrorAction SilentlyContinue
        Remove-Item Env:TF_BOOTSTRAP_ADMIN_USERNAME -ErrorAction SilentlyContinue
        Remove-Item Env:TF_BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
        Remove-Item Env:TF_REDIS_PASSWORD -ErrorAction SilentlyContinue
    }
}

function Invoke-TestFlowSql {
    param([Parameter(Mandatory)][string]$Sql)
    Set-TestFlowDockerEnvironment
    Push-Location $script:TestFlowRoot
    try {
        $Sql | & docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'
        if ($LASTEXITCODE -ne 0) { throw 'Local database administration failed; credentials are not printed.' }
    } finally { Pop-Location }
}

function Grant-TestFlowWorker {
    param([switch]$TestDatabase)
    $database = if ($TestDatabase) { 'testflow_test' } else { 'testflow' }
    $worker = if ($TestDatabase) { 'tf_test_worker' } else { 'tf_worker' }
    Invoke-TestFlowSql -Sql "GRANT INSERT, UPDATE ON $database.tf_worker_node TO '$worker'@'%';"
    Invoke-TestFlowSql -Sql "GRANT INSERT ON $database.tf_outbox_event TO '$worker'@'%';"
    Invoke-TestFlowSql -Sql "GRANT SELECT, UPDATE ON $database.tf_test_run TO '$worker'@'%'; GRANT SELECT, UPDATE ON $database.tf_run_item TO '$worker'@'%'; GRANT SELECT, INSERT ON $database.tf_run_item_result TO '$worker'@'%';"
}


