<#
.SYNOPSIS
在隔离副本中验证工具语法、路径、启动编排及失败退出。
.DESCRIPTION
不要求Docker引擎，不连接数据库。以命令替身代替Docker、Java、npm和Maven，防止把编排测试变成真实部署。
.NOTES
需要PowerShell7和Node；结果在.local/verification/tooling，隔离副本含随机测试凭据，不可公开。
#>
#requires -Version 7.0
. (Join-Path $PSScriptRoot '../lib/ProjectPaths.ps1')
$ErrorActionPreference = 'Stop'
$checks = [Collections.Generic.List[string]]::new()
function Assert-Tool([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $checks.Add($Message)
}

$scriptFiles = @(Get-ChildItem (Join-Path $script:TestFlowRoot 'scripts') -Recurse -Filter *.ps1)
foreach ($file in $scriptFiles) {
    $tokens=$null; $parseErrors=$null
    $null=[Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$parseErrors)
    Assert-Tool ($parseErrors.Count -eq 0) "PowerShell syntax: $($file.Name)"
    Assert-Tool ((Get-Content -LiteralPath $file.FullName -Raw).Contains('.SYNOPSIS')) "Chinese help: $($file.Name)"
}
foreach ($file in Get-ChildItem (Join-Path $script:TestFlowRoot 'scripts') -Recurse -File | Where-Object Extension -In '.js','.mjs') {
    & node --check $file.FullName
    Assert-Tool ($LASTEXITCODE -eq 0) "JavaScript syntax: $($file.Name)"
}

$directory=Join-Path $script:TestFlowRoot ('.local/verification/tooling/'+[guid]::NewGuid().ToString('N'))
$fixture=Join-Path $directory 'repository with spaces'
$elsewhere=Join-Path $directory 'outside working directory'
$null=New-Item -ItemType Directory -Path $fixture,$elsewhere -Force
Copy-Item -LiteralPath (Join-Path $script:TestFlowRoot 'scripts') -Destination $fixture -Recurse
Copy-Item -LiteralPath (Join-Path $script:TestFlowRoot 'pom.xml') -Destination $fixture
$null=New-Item -ItemType Directory -Path (Join-Path $fixture 'frontend'),(Join-Path $fixture '.local') -Force
foreach ($app in 'api','worker') {
    $target=Join-Path $fixture "testflow-$app/target"
    $null=New-Item -ItemType Directory -Path $target -Force
    [IO.File]::WriteAllText((Join-Path $target "testflow-$app-0.1.0-SNAPSHOT.jar"),'fake artifact: never executed')
}
$fake=@{MysqlPort=13306;RedisPort=6379}
foreach($key in @('RootPassword','ApiPassword','WorkerPassword','MigrationPassword','RedisPassword','AdminPassword','SecretKey','SnapshotHmacKey','TestApiPassword','TestWorkerPassword','TestMigrationPassword','TestAdminPassword')) {
    $fake[$key]=[guid]::NewGuid().ToString('N')
}
[IO.File]::WriteAllText((Join-Path $fixture '.local/dev-secrets.json'),($fake|ConvertTo-Json))
[IO.File]::WriteAllText((Join-Path $fixture 'mvnw.cmd'),"@echo off`r`necho maven>>`"%TOOL_TRACE_NATIVE%`"`r`nexit /b %TOOL_NATIVE_EXIT%`r`n")
[IO.File]::WriteAllText((Join-Path $fixture 'mvnw'),'echo maven >> "$TOOL_TRACE_NATIVE"'+"`n"+'exit "$TOOL_NATIVE_EXIT"'+"`n")

$runner=Join-Path $directory 'run-isolated.ps1'
[IO.File]::WriteAllText($runner,@'
param([string]$Fixture,[string]$Elsewhere,[string]$Case,[string]$Trace,[string]$Failure='')
$ErrorActionPreference='Stop'
$env:TOOL_TRACE_NATIVE=$Trace+'.native'
$env:TOOL_NATIVE_EXIT=if($Failure -eq 'maven'){'9'}else{'0'}
function Record([string]$Name,$Arguments) {
    $v=@{command=$Name;arguments=@($Arguments);cwd=(Get-Location).Path}
    if($Name -eq 'java') { $v.apiPort=$env:TF_API_PORT; $v.dbUser=$env:TF_DB_USERNAME; $v.hasMigrationCredentials=Test-Path Env:TF_MIGRATION_DB_PASSWORD }
    [IO.File]::AppendAllText($Trace,($v|ConvertTo-Json -Compress)+"`n")
}
function docker {
    Record docker $args
    $global:LASTEXITCODE=0
    if (($Failure -eq 'build' -and $args -contains 'build') -or ($Failure -eq 'config' -and $args -contains 'config')) { $global:LASTEXITCODE=7; return }
    if ($args -contains 'ps') { 'synthetic-container' }
    if ($args[0] -eq 'wait') { '0' }
}
function java { Record java $args; $global:LASTEXITCODE=0 }
function npm { Record npm $args; $global:LASTEXITCODE=if($Failure -eq 'npm' -and $args -contains 'build'){8}else{0} }
function icacls { $global:LASTEXITCODE=0 }
function chmod { $global:LASTEXITCODE=0 }
Set-Location -LiteralPath $Elsewhere
switch ($Case) {
    start { & (Join-Path $Fixture 'scripts/Start-Deployment.ps1') -Demo }
    skip { & (Join-Path $Fixture 'scripts/Start-Deployment.ps1') -Demo -SkipBuild }
    stop { & (Join-Path $Fixture 'scripts/Stop-Deployment.ps1') }
    api { & (Join-Path $Fixture 'scripts/dev/Start-Api.ps1') -Port 18082 }
    worker { & (Join-Path $Fixture 'scripts/dev/Start-Worker.ps1') }
    verify { & (Join-Path $Fixture 'scripts/Verify-CI.ps1') }
    default { throw 'Unknown isolated test case' }
}
'@)

function Invoke-Isolated([string]$Case,[string]$Failure='') {
    $trace=Join-Path $directory "$Case-$Failure.jsonl"
    $out=Join-Path $directory "$Case-$Failure.out.log"
    $err=Join-Path $directory "$Case-$Failure.err.log"
    & (Join-Path $PSHOME $(if($IsWindows){'pwsh.exe'}else{'pwsh'})) -NoProfile -File $runner -Fixture $fixture -Elsewhere $elsewhere -Case $Case -Trace $trace -Failure $Failure > $out 2> $err
    $code=$LASTEXITCODE
    $records=if(Test-Path -LiteralPath $trace){@(Get-Content -LiteralPath $trace | ForEach-Object { $_|ConvertFrom-Json })}else{@()}
    Assert-Tool (($code -eq 0) -eq ($Failure -eq '')) "Exit propagation: $Case/$Failure"
    return @{Records=$records;Trace=$trace}
}
$normal=Invoke-Isolated start
Assert-Tool (@($normal.Records|Where-Object {$_.arguments -contains 'build'}).Count -eq 1) 'Deployment builds once'
Assert-Tool ($normal.Records[0].arguments -contains 'config') 'Deployment initializes and validates first'
Assert-Tool (@($normal.Records|Where-Object {$_.arguments -contains 'kafka-topic-init'}).Count -eq 2) 'Deployment starts and locates topic initialization'
Assert-Tool (@($normal.Records|Where-Object {$_.arguments[0] -eq 'wait'}).Count -eq 1) 'Deployment waits for topic initialization exit'
Assert-Tool (@($normal.Records|Where-Object {$_.cwd -ne $fixture}).Count -eq 0) 'Deployment uses project root from outside directory'
$skip=Invoke-Isolated skip
Assert-Tool (@($skip.Records|Where-Object {$_.arguments -contains 'build'}).Count -eq 0) 'SkipBuild never builds'
Assert-Tool (@($skip.Records|Where-Object {$_.arguments -contains 'up'}).Count -eq 2) 'SkipBuild still starts and initializes topic'
foreach ($failure in 'build','config') {
    $failed=Invoke-Isolated start $failure
    Assert-Tool (@($failed.Records|Where-Object {$_.arguments -contains 'up'}).Count -eq 0) "Deployment stops before up on $failure failure"
}
$stop=Invoke-Isolated stop
$groups=@($stop.Records|ForEach-Object { (@($_.arguments)[8..($_.arguments.Count-1)]) -join ',' })
Assert-Tool (($groups -join ';') -eq 'nginx;worker,demo-target;api;kafka;redis;mysql') 'Stop order and target services preserved'
Assert-Tool (@($stop.Records|Where-Object {$_.arguments -contains 'down' -or $_.arguments -contains '-v'}).Count -eq 0) 'Stop never removes volumes'
$api=Invoke-Isolated api
Assert-Tool (@($api.Records|Where-Object command -eq java).Count -eq 1) 'API invokes Java with absolute artifact path'
$apiJava=@($api.Records|Where-Object command -eq java)[0]
Assert-Tool ($apiJava.arguments[-1] -eq (Join-Path $fixture 'testflow-api/target/testflow-api-0.1.0-SNAPSHOT.jar') -and $apiJava.apiPort -eq '18082') 'API preserves explicit port and absolute artifact'
$worker=Invoke-Isolated worker
Assert-Tool (@($worker.Records|Where-Object command -eq java).Count -eq 1) 'Worker invokes Java after permission preparation'
$workerJava=@($worker.Records|Where-Object command -eq java)[0]
Assert-Tool ($workerJava.dbUser -eq 'tf_worker' -and -not $workerJava.hasMigrationCredentials) 'Worker retains runtime identity without migration credentials'
$verify=Invoke-Isolated verify
Assert-Tool ((Test-Path -LiteralPath ($verify.Trace+'.native'))) 'Unified verification invokes Maven wrapper'
Assert-Tool (@($verify.Records|Where-Object {$_.command -eq 'npm' -and $_.arguments -contains 'build'}).Count -eq 1) 'Unified verification builds frontend once'
$failed=Invoke-Isolated verify maven
Assert-Tool (@($failed.Records|Where-Object command -eq npm).Count -eq 0) 'Maven failure prevents frontend execution'
$null=Invoke-Isolated verify npm

$summary=@{at=[DateTime]::UtcNow.ToString('o');passed=$checks.Count;scope='isolated command substitutes, no real service calls';checks=@($checks)}
$result=Join-Path $script:TestFlowRoot '.local/verification/tooling/summary.json'
[IO.File]::WriteAllText($result,($summary|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
Write-Host "PASS: $($checks.Count) tooling checks; isolated copies retained in ignored .local/verification/tooling."
exit 0
