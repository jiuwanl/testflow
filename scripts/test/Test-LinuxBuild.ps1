<#
.SYNOPSIS
在Windows Docker Desktop中复现Linux Java集成构建。
.DESCRIPTION
需要可用开发测试依赖；临时凭据通过BuildKit secret传入，不写镜像层。
.NOTES
可能下载依赖，结果：.local/verification/linux-build；临时秘密文件结束后清理。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
# Windows Docker Desktop reproduction of the Linux Java CI build.
$ErrorActionPreference='Stop'
if(-not $IsWindows){throw 'Use Verify-CI.ps1 natively on Linux; this helper targets Docker Desktop on Windows.'}
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
Set-TestFlowEnvironment -Role Test
$env:TF_DB_URL=$env:TF_DB_URL.Replace('127.0.0.1','host.docker.internal')
$env:TF_REDIS_HOST='host.docker.internal'
$env:TF_TEST_WORKER_PASSWORD=(Get-TestFlowConfig).TestWorkerPassword
$directory=Join-Path $script:TestFlowRoot '.local/verification/linux-build'
$null=New-Item -ItemType Directory -Path $directory -Force
$environmentPath=Join-Path $directory 'linux-ci.env'
try {
    $lines=@(Get-ChildItem Env:TF_* | Where-Object Name -NotLike 'TF_LOCAL_*' | ForEach-Object {
        if($_.Value.Contains("'") -or $_.Value.Contains("`n") -or $_.Value.Contains("`r")){throw 'Unexpected shell control character in test configuration.'}
        "export $($_.Name)='$($_.Value)'"
    })
    [IO.File]::WriteAllText($environmentPath,($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls $environmentPath /inheritance:r /grant:r ("${identity}:(F)") | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Cannot restrict temporary CI credentials.'}
    Push-Location $script:TestFlowRoot
    try {
        & docker build --no-cache-filter verify -f scripts/test/support/Dockerfile.verify --secret "id=ci_env,src=$environmentPath" --output type=local,dest=.local/verification/linux-build/reports .
        if($LASTEXITCODE -ne 0){throw 'Linux Java verification failed; no tests skipped.'}
    } finally {Pop-Location}
} finally {
    if(Test-Path -LiteralPath $environmentPath){Remove-Item -LiteralPath $environmentPath}
}
