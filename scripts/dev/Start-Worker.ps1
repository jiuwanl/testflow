<#
.SYNOPSIS
在Windows运行已打包的独立Worker。
.DESCRIPTION
先让API完成迁移；脚本准备最小运行权限，Worker不执行Flyway。
.NOTES
会领取开发库中待执行任务并发送获授权HTTP；前台运行，Ctrl+C结束。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
# The API must have successfully migrated first. Only the operator grants runtime permissions.
Grant-TestFlowWorker
Set-TestFlowEnvironment -Role Worker
$jar = Join-Path $script:TestFlowRoot 'testflow-worker/target/testflow-worker-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build first: .\mvnw.cmd -B clean verify' }
& java '-Xms128m' '-Xmx512m' -jar $jar
exit $LASTEXITCODE

