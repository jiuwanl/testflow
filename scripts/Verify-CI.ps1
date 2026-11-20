<#
.SYNOPSIS
统一执行Java单元和集成测试、前端类型检查及生产构建。
.DESCRIPTION
先准备独立开发/测试MySQL和Redis；使用testflow_test，不使用业务库。
.NOTES
会清理专用测试数据并更新target/node_modules/dist；Java报告在target，失败立即停止。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/LocalEnvironment.ps1')
Set-TestFlowEnvironment -Role Test
$env:TF_TEST_WORKER_PASSWORD = (Get-TestFlowConfig).TestWorkerPassword
Push-Location $script:TestFlowRoot
try {
    if ($IsWindows) { & ./mvnw.cmd -B -ntp -Pintegration clean verify }
    else { & bash ./mvnw -B -ntp -Pintegration clean verify }
    if ($LASTEXITCODE -ne 0) { throw 'Java unit/integration verification failed.' }
    Push-Location frontend
    try {
        & npm ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Locked frontend dependency installation failed.' }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend type check/build failed.' }
    } finally { Pop-Location }
} finally { Pop-Location }
