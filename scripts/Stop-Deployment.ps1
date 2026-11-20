<#
.SYNOPSIS
按顺序停止完整Docker部署，保留数据卷。
.DESCRIPTION
先关闭入口，再停止Worker、API、消息和存储；不退出Docker Desktop。
.NOTES
会中断本项目服务；建议先等待运行结束，不删除数据。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
. (Join-Path $PSScriptRoot 'lib/ProjectPaths.ps1')
$ErrorActionPreference = 'Stop'
Push-Location ($script:TestFlowRoot)
try {
    # Stop request entry, then executor, then API, then messaging and storage. Never remove volumes.
    foreach ($services in @(@('nginx'), @('worker', 'demo-target'), @('api'), @('kafka'), @('redis'), @('mysql'))) {
        Write-Host "Stopping: $($services -join ', ')"
        & docker compose -f compose.deploy.yml --profile demo stop --timeout 35 @services
        if ($LASTEXITCODE -ne 0) { throw 'Stop failed; inspect service status before continuing.' }
    }
} finally { Pop-Location }
