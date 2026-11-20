<#
.SYNOPSIS
构建并启动完整Docker部署，是日常启动入口。
.DESCRIPTION
需要Docker Desktop Linux引擎就绪；自动准备部署配置，默认构建镜像，等待服务及Kafka主题初始化完成。
.PARAMETER Demo
启用合成HTTP目标；不会自动创建项目或执行测试。
.PARAMETER SkipBuild
仅复用已有镜像；代码或构建配置更新后不可使用。
.NOTES
会启动容器、下载或构建镜像；已有数据库及密码保留。秘密：.local/deploy-secrets.json。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([switch]$Demo, [switch]$SkipBuild)
. (Join-Path $PSScriptRoot 'lib/ProjectPaths.ps1')
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'deployment/Initialize-Deployment.ps1')
Push-Location ($script:TestFlowRoot)
try {
    $composeArguments = @('compose', '-f', 'compose.deploy.yml')
    if ($Demo) { $composeArguments += @('--profile', 'demo') }
    if (-not $SkipBuild) {
        & docker @composeArguments build
        if ($LASTEXITCODE -ne 0) { throw 'Image build failed; existing volumes preserved.' }
    }
    $services = @('api', 'worker', 'nginx', 'kafka')
    if ($Demo) { $services += 'demo-target' }
    & docker @composeArguments up -d --wait --wait-timeout 240 @services
    if ($LASTEXITCODE -ne 0) { throw 'Deployment not healthy; inspect compose ps/logs. No volumes removed.' }
    & docker @composeArguments up -d kafka-topic-init
    if ($LASTEXITCODE -ne 0) { throw 'Topic initialization failed.' }
    $topicContainer = & docker @composeArguments ps -a -q kafka-topic-init
    $topicExit = & docker wait $topicContainer
    if ($LASTEXITCODE -ne 0 -or "$topicExit" -ne '0') { throw 'Topic initialization did not complete successfully.' }
    Write-Host 'TestFlow ready at http://127.0.0.1:18081 (admin credential in ignored .local/deploy-secrets.json).'
} finally { Pop-Location }
