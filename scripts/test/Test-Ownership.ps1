<#
.SYNOPSIS
验证所有权治理、账号禁用和交接接口。
.DESCRIPTION
需要已构建API及独立MySQL/Redis；准备合成账号、项目和浏览器夹具。
.PARAMETER KeepApi
专项通过后保留测试API并记录PID，供浏览器专项使用。
.PARAMETER Port
API监听端口；开发默认8080，所有权专项默认18080。
.NOTES
默认结束测试API；KeepApi为后续浏览器保留API。结果：.local/verification/ownership。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([switch]$KeepApi, [int]$Port=18080)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
Set-TestFlowEnvironment -Role Test
if (-not $env:TF_DB_URL.Contains('/testflow_test?')) { throw 'Dedicated test database required.' }
$env:TF_API_PORT=[string]$Port
$env:TF_OWNERSHIP_URL="http://127.0.0.1:$Port"
$evidence=Join-Path $script:TestFlowRoot '.local/verification/ownership'
$null=New-Item -ItemType Directory -Path $evidence -Force
$jar=Join-Path $script:TestFlowRoot 'testflow-api/target/testflow-api-0.1.0-SNAPSHOT.jar'
$child=Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-Xmx384m','-Dfile.encoding=UTF-8','-jar',('"'+$jar+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidence 'api.out.log') -RedirectStandardError (Join-Path $evidence 'api.err.log')
$verified=$false
try {
    $ready=$false
    for($attempt=0;$attempt -lt 120;$attempt++) {
        $child.Refresh(); if($child.HasExited){ throw 'Ownership API exited; inspect private logs.' }
        try { if((Invoke-RestMethod "$env:TF_OWNERSHIP_URL/actuator/health" -TimeoutSec 2).status -eq 'UP'){ $ready=$true; break } } catch {}
        Start-Sleep -Milliseconds 500
    }
    if(-not $ready){ throw 'Ownership API readiness timed out.' }
    & node (Join-Path $PSScriptRoot 'support/ownership-check.mjs')
    if($LASTEXITCODE -ne 0){ throw 'Ownership process regression failed.' }
    $verified=$true
    if($KeepApi){ [IO.File]::WriteAllText((Join-Path $evidence 'api.pid'), [string]$child.Id) }
} finally {
    $child.Refresh()
    if((-not $KeepApi -or -not $verified) -and -not $child.HasExited){ Stop-Process -InputObject $child; $child.WaitForExit() }
}
