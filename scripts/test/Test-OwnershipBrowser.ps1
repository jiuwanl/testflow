<#
.SYNOPSIS
在真实浏览器验证治理、禁用交接和自身操作提示。
.DESCRIPTION
先准备新所有权夹具、测试API18080、代理到它的Vite和ownership命名Playwright会话。
.PARAMETER Phase
选择测试步骤或SQL对照阶段；每次仅传一个受支持值。
.NOTES
会修改合成夹具；不得重复使用已完成交接的数据。结果：.local/verification/ownership及output/playwright。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([ValidateSet('governance','disable','self','responsive','viewer')][string]$Phase='governance')
. (Join-Path $PSScriptRoot '../lib/ProjectPaths.ps1')
$ErrorActionPreference='Stop'
$root=$script:TestFlowRoot
$privateDir=Join-Path $root '.local/verification/ownership'
$fixture=Get-Content -LiteralPath (Join-Path $privateDir 'browser-fixture.json') -Raw | ConvertFrom-Json
$config=Get-Content -LiteralPath (Join-Path $root '.local/dev-secrets.json') -Raw | ConvertFrom-Json
$fixture | Add-Member -NotePropertyName adminPassword -NotePropertyValue $config.TestAdminPassword
$fixture | Add-Member -NotePropertyName phase -NotePropertyValue $Phase
$template=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'support/ownership-browser.js') -Raw
$code=$template.Replace('/* TEST_FIXTURE */ null',($fixture | ConvertTo-Json -Depth 10 -Compress))
$temporary=Join-Path $privateDir "browser-$Phase.private.js"
$log=Join-Path $privateDir "browser-$Phase.private.log"
$null=New-Item -ItemType Directory -Path (Join-Path $root 'output/playwright') -Force
$succeeded=$false
Push-Location $root
try {
    [IO.File]::WriteAllText($temporary,$code,[Text.UTF8Encoding]::new($false))
    # Offline CLI uses an existing installation/cache; this does not install another browser or framework.
    $cli=Get-Command playwright-cli -ErrorAction SilentlyContinue
    if($cli) { & $cli.Source -s=ownership run-code --filename $temporary *> $log }
    else {
        $cached=Get-ChildItem (Join-Path $env:LOCALAPPDATA 'npm-cache/_npx') -Filter playwright-cli.cmd -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if(-not $cached){ throw 'Install/configure the Playwright CLI before browser verification.' }
        & $cached.FullName -s=ownership run-code --filename $temporary *> $log
    }
    if($LASTEXITCODE -ne 0){ throw 'Browser CLI failed; inspect private log.' }
    $text=Get-Content -LiteralPath $log -Raw
    if($text.Contains('### Error')){ throw 'Browser assertions failed; inspect private log.' }
    $match=[regex]::Match($text,'(?s)### Result\r?\n(.+?)\r?\n### Ran Playwright code')
    if(-not $match.Success){ throw 'Browser CLI did not return a structured result.' }
    $result=$match.Groups[1].Value | ConvertFrom-Json
    [IO.File]::WriteAllText((Join-Path $privateDir "browser-$Phase.json"),($result | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
    $succeeded=$true
    $result | ConvertTo-Json -Depth 10
} finally {
    # Temporary code and raw CLI output can include injected fixture credentials.
    Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    if($succeeded){ Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue }
    Pop-Location
}
