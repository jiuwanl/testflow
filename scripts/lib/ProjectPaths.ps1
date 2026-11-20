<#
.SYNOPSIS
定位项目根目录，供其他工具加载，不作为日常入口。
.DESCRIPTION
只按本文件所在位置计算路径并核对根POM，不读取凭据、不启动服务。
#>
#requires -Version 7.0
$script:TestFlowRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not (Test-Path -LiteralPath (Join-Path $script:TestFlowRoot 'pom.xml'))) {
    throw 'TestFlow project root not found; keep scripts/lib inside the repository.'
}
