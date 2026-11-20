<#
.SYNOPSIS
准备Windows开发用MySQL、Redis及独立测试库。
.DESCRIPTION
生成或复用本机随机凭据，启动开发Compose依赖并建立分角色账号。
.PARAMETER MysqlPort
开发MySQL发布到Windows的端口，默认13306。
.PARAMETER RedisPort
开发Redis发布端口，默认6379。
.NOTES
会创建数据库和账号，不清空已有业务数据；秘密：.local/dev-secrets.json。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([int]$MysqlPort = 13306, [int]$RedisPort = 6379)
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
$localDir = Join-Path $script:TestFlowRoot '.local'
$secretsPath = Join-Path $localDir 'dev-secrets.json'
if (-not (Test-Path -LiteralPath $secretsPath)) {
    $null = New-Item -ItemType Directory -Path $localDir -Force
    function New-RandomHex { [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant() }
    $config = @{
        RootPassword=New-RandomHex; ApiPassword=New-RandomHex; WorkerPassword=New-RandomHex
        MigrationPassword=New-RandomHex; TestApiPassword=New-RandomHex; TestWorkerPassword=New-RandomHex
        TestMigrationPassword=New-RandomHex; RedisPassword=New-RandomHex; AdminPassword=New-RandomHex
        TestAdminPassword=New-RandomHex; SecretKey=[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        SnapshotHmacKey=[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        MysqlPort=$MysqlPort; RedisPort=$RedisPort
    }
    [System.IO.File]::WriteAllText($secretsPath, ($config | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
    if ($IsWindows) {
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        $null = & icacls $secretsPath /inheritance:r /grant:r ("${identity}:(F)")
        if ($LASTEXITCODE -ne 0) { throw 'Could not restrict local secrets file permissions.' }
    }
}
$config = Get-TestFlowConfig
if (-not $config.PSObject.Properties['SnapshotHmacKey']) {
    $config | Add-Member -NotePropertyName SnapshotHmacKey -NotePropertyValue ([Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))
}
if ($PSBoundParameters.ContainsKey('MysqlPort')) { $config.MysqlPort = $MysqlPort }
if ($PSBoundParameters.ContainsKey('RedisPort')) { $config.RedisPort = $RedisPort }
[System.IO.File]::WriteAllText($secretsPath, ($config | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
Set-TestFlowDockerEnvironment
Push-Location $script:TestFlowRoot
try {
    & docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed.' }
    & docker compose up -d --wait --wait-timeout 180 mysql redis
    if ($LASTEXITCODE -ne 0) { throw 'Dependencies did not become healthy; existing data was not removed.' }
    # Passwords are generated hex values; never interpolate user-controlled identifiers or log this SQL.
    $sql = @"
CREATE DATABASE IF NOT EXISTS testflow CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS testflow_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'tf_migrator'@'%' IDENTIFIED BY '$($config.MigrationPassword)';
CREATE USER IF NOT EXISTS 'tf_api'@'%' IDENTIFIED BY '$($config.ApiPassword)';
CREATE USER IF NOT EXISTS 'tf_worker'@'%' IDENTIFIED BY '$($config.WorkerPassword)';
CREATE USER IF NOT EXISTS 'tf_test_migrator'@'%' IDENTIFIED BY '$($config.TestMigrationPassword)';
CREATE USER IF NOT EXISTS 'tf_test_api'@'%' IDENTIFIED BY '$($config.TestApiPassword)';
CREATE USER IF NOT EXISTS 'tf_test_worker'@'%' IDENTIFIED BY '$($config.TestWorkerPassword)';
GRANT ALL PRIVILEGES ON testflow.* TO 'tf_migrator'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON testflow.* TO 'tf_api'@'%';
GRANT SELECT ON testflow.* TO 'tf_worker'@'%';
GRANT ALL PRIVILEGES ON testflow_test.* TO 'tf_test_migrator'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON testflow_test.* TO 'tf_test_api'@'%';
GRANT SELECT ON testflow_test.* TO 'tf_test_worker'@'%';
"@
    Invoke-TestFlowSql -Sql $sql
    Write-Host 'MySQL/Redis ready. Random local credentials are in ignored .local/dev-secrets.json (not printed).'
} finally { Pop-Location }

