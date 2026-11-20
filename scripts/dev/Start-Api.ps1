<#
.SYNOPSIS
在Windows运行已打包的API。
.DESCRIPTION
需要JDK21、已构建JAR以及开发MySQL/Redis；API唯一执行数据库迁移。
.PARAMETER Port
API监听端口；开发默认8080，所有权专项默认18080。
.NOTES
使用开发凭据；前台运行，Ctrl+C结束，不启动Worker或前端。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([int]$Port = 8080)
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
Set-TestFlowEnvironment -Role Api
$env:TF_API_PORT = [string]$Port
$jar = Join-Path $script:TestFlowRoot 'testflow-api/target/testflow-api-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build first: .\mvnw.cmd -B clean verify' }
& java '-Xms128m' '-Xmx512m' -jar $jar
exit $LASTEXITCODE

