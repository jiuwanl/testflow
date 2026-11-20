<#
.SYNOPSIS
内部部署辅助：准备秘密文件及校验Compose配置。
.DESCRIPTION
由Start-Deployment自动调用；只生成或复用配置，不负责完整启动。
.NOTES
会写.local/deploy-secrets目录和限制文件权限；保留既有随机凭据，不打印值。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
. (Join-Path $PSScriptRoot '../lib/ProjectPaths.ps1')
$ErrorActionPreference = 'Stop'
$root = $script:TestFlowRoot
$directory = Join-Path $root '.local/deploy-secrets'
$configPath = Join-Path $root '.local/deploy-secrets.json'
if (-not (Test-Path -LiteralPath $configPath)) {
    function New-Hex { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant() }
    $config = @{
        RootPassword = New-Hex; ApiPassword = New-Hex; WorkerPassword = New-Hex
        MigrationPassword = New-Hex; RedisPassword = New-Hex; AdminPassword = New-Hex
        SecretKey = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        SnapshotHmacKey = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    }
    $null = New-Item -ItemType Directory -Path (Split-Path $configPath) -Force
    [IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json))
}
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$null = New-Item -ItemType Directory -Path $directory -Force
$files = @{
    mysql_root_password = $config.RootPassword; api_password = $config.ApiPassword
    worker_password = $config.WorkerPassword; migration_password = $config.MigrationPassword
    redis_password = $config.RedisPassword; admin_password = $config.AdminPassword
    secret_key = $config.SecretKey; snapshot_hmac_key = $config.SnapshotHmacKey
    redis_config = "bind 0.0.0.0`nprotected-mode yes`nrequirepass $($config.RedisPassword)`nmaxmemory 128mb`nmaxmemory-policy noeviction`nappendonly yes`nappendfsync everysec`ndir /data`n"
}
foreach ($entry in $files.GetEnumerator()) {
    [IO.File]::WriteAllText((Join-Path $directory $entry.Key), $entry.Value, [Text.UTF8Encoding]::new($false))
}
if ($IsWindows) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls $directory /inheritance:r /grant:r ("${identity}:(OI)(CI)F") | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot restrict deployment secrets directory.' }
    & icacls $configPath /inheritance:r /grant:r ("${identity}:(F)") | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot restrict deployment credentials.' }
} else {
    & chmod 700 $directory
    & chmod 600 $configPath
    # Compose file secrets are bind mounts: non-root application users must read them.
    # The parent directory is owner-only, so host users cannot traverse to these files.
    & chmod 644 @((Get-ChildItem -LiteralPath $directory -File).FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot set secret file permissions.' }
}
Push-Location $root
try {
    & docker compose -f compose.deploy.yml config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Deployment configuration invalid.' }
} finally { Pop-Location }
Write-Host 'Deployment secrets initialized (values not printed); existing credentials preserved.'
