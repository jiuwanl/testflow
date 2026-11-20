<#
.SYNOPSIS
验证有序计划、运行变量、失败停止和原快照重跑。
.DESCRIPTION
需要已构建JAR、开发依赖和独立测试库；测试进程结束后清理。
.PARAMETER ApiPort
本工具连接或启动的API端口，按脚本默认值使用。
.PARAMETER TargetPort
本机合成HTTP目标端口，按脚本默认值使用。
.NOTES
会创建合成记录，结果：.local/verification/ordered-plan；每次仅运行一个专项。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([int]$ApiPort=18080,[int]$TargetPort=18088)
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
$ErrorActionPreference='Stop'
$config=Get-TestFlowConfig
$evidence=Join-Path $script:TestFlowRoot '.local/verification/ordered-plan'
$null=New-Item -ItemType Directory -Path $evidence -Force
$java=(Get-Command java).Source
$apiJar=Join-Path $script:TestFlowRoot 'testflow-api/target/testflow-api-0.1.0-SNAPSHOT.jar'
$workerJar=Join-Path $script:TestFlowRoot 'testflow-worker/target/testflow-worker-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $apiJar) -or -not (Test-Path -LiteralPath $workerJar)) { throw 'Build API and Worker first.' }
$root="http://127.0.0.1:$ApiPort/api/v1"
$target="http://127.0.0.1:$TargetPort"
$children=[Collections.Generic.List[System.Diagnostics.Process]]::new()
$checks=[Collections.Generic.List[string]]::new()
$script:session=$null

function Start-Child([string]$Name,[string[]]$Arguments) {
    $process=Start-Process -FilePath $java -ArgumentList $Arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $evidence "$Name.out.log") -RedirectStandardError (Join-Path $evidence "$Name.err.log")
    $children.Add($process)
    return $process
}
function Stop-Child($Process) {
    if ($null -eq $Process) { return }
    $Process.Refresh()
    if (-not $Process.HasExited) { Stop-Process -InputObject $Process -ErrorAction Stop; $Process.WaitForExit() }
}
function Set-TestEnvironment {
    Set-TestFlowEnvironment -Role Test
    if (-not $env:TF_DB_URL.Contains('/testflow_test?')) { throw 'Only the dedicated test database is permitted.' }
    $env:TF_API_PORT=[string]$ApiPort
    $env:TF_RUN_TIMEOUT_MS='120000'
    $env:TF_RECOVERY_INTERVAL_MS='500'
    $env:TF_HEARTBEAT_MS='500'
    $env:TF_LEASE_MS='3000'
    $env:TF_POLL_INTERVAL_MS='200'
}
function Start-Api([string]$Name) {
    Set-TestEnvironment
    $process=Start-Child $Name @('-Xms128m','-Xmx512m','-jar',('"'+$apiJar+'"'))
    for ($i=0;$i -lt 150;$i++) {
        $process.Refresh(); if ($process.HasExited) { throw 'Test API exited; inspect private process logs.' }
        try { if ((Invoke-RestMethod "http://127.0.0.1:$ApiPort/actuator/health" -TimeoutSec 2).status -eq 'UP') { return $process } } catch { }
        Start-Sleep -Milliseconds 250
    }
    throw 'Test API did not become ready.'
}
function Start-Worker([string]$Name) {
    Grant-TestFlowWorker -TestDatabase
    Set-TestEnvironment
    $env:TF_DB_USERNAME='tf_test_worker'; $env:TF_DB_PASSWORD=$config.TestWorkerPassword
    foreach ($keyName in @('TF_MIGRATION_DB_USERNAME','TF_MIGRATION_DB_PASSWORD','TF_BOOTSTRAP_ADMIN_USERNAME','TF_BOOTSTRAP_ADMIN_PASSWORD','TF_REDIS_PASSWORD','TF_LOCAL_MYSQL_ROOT_PASSWORD','TF_LOCAL_REDIS_PASSWORD')) {
        Remove-Item -LiteralPath "Env:$keyName" -ErrorAction SilentlyContinue
    }
    return Start-Child $Name @('-Xms128m','-Xmx512m','-jar',('"'+$workerJar+'"'))
}
function Response-Json($Response) {
    $text=if ($Response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($Response.Content) } else { [string]$Response.Content }
    return $text | ConvertFrom-Json -Depth 40
}
function Login {
    $web=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrf=(Response-Json (Invoke-WebRequest "$root/auth/csrf" -WebSession $web)).data
    $body=@{username='test-admin';password=$config.TestAdminPassword} | ConvertTo-Json -Compress
    $null=Invoke-WebRequest "$root/auth/login" -Method Post -WebSession $web -ContentType application/json -Headers @{$csrf.headerName=$csrf.token} -Body $body
    $csrf=(Response-Json (Invoke-WebRequest "$root/auth/csrf" -WebSession $web)).data
    $script:session=@{Web=$web;Csrf=$csrf}
}
function Api([string]$Method,[string]$Path,$Body=$null,[int]$Expected=200,[string]$Key='') {
    $headers=@{}; if ($Method -ne 'GET') { $headers[$script:session.Csrf.headerName]=$script:session.Csrf.token }
    if ($Key) { $headers['Idempotency-Key']=$Key }
    $arguments=@{Uri="$root$Path";Method=$Method;WebSession=$script:session.Web;Headers=$headers;SkipHttpErrorCheck=$true;TimeoutSec=20}
    if ($null -ne $Body) { $arguments.ContentType='application/json'; $arguments.Body=$Body | ConvertTo-Json -Depth 30 -Compress }
    $response=Invoke-WebRequest @arguments
    if ([int]$response.StatusCode -ne $Expected) { throw "Unexpected HTTP $($response.StatusCode) for $Method $Path (expected $Expected). Payload omitted." }
    $parsed=Response-Json $response
    if($Expected -ge 400){return $parsed.error}
    return $parsed.data
}
function Assert([bool]$Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Pass([string]$Name) { $checks.Add($Name); Write-Host "PASS: $Name" }
function Stats { return Invoke-RestMethod "$target/stats" -TimeoutSec 3 }
function Count([string]$Path) {
    $statistics=Stats
    $property=$statistics.requests.PSObject.Properties[$Path]
    if ($null -eq $property) { return 0 }; return [int]$property.Value
}
function Case([string]$Name,[string]$Path,[int]$Timeout=15000,[string]$Method='GET') {
    return (Api POST "$script:base/cases" @{name=$Name;configSchemaVersion=1;config=@{
        request=@{method=$Method;path=$Path;query=@();headers=@();body=@{type='NONE'}}
        assertions=@(@{id='status';type='STATUS_CODE';expected=200});extractors=@();timeoutMs=$Timeout
    }} 201).id
}
function Submit([string]$CaseId,[string]$EnvironmentId=$script:environment,[string]$Key=([guid]::NewGuid().ToString())) {
    return Api POST "$script:base/runs" @{sourceType='CASE';sourceId=$CaseId;environmentId=$EnvironmentId} 202 $Key
}
function Wait-Run([string]$Id,[string]$State,[int]$Seconds=25) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $run=Api GET "$script:base/runs/$Id"
        if ($run.state -eq $State) { return $run }
        if ($run.state -in @('COMPLETED','CANCELLED','TIMED_OUT','INTERRUPTED')) { throw "Run $Id ended as $($run.state), expected $State." }
        Start-Sleep -Milliseconds 150
    }
    throw "Run $Id did not reach $State within the observation interval."
}
function Wait-Mutations([int]$Expected) {
    for ($i=0;$i -lt 100;$i++) { if ((Stats).mutations -ge $Expected) { return }; Start-Sleep -Milliseconds 100 }
    throw 'Fixture did not observe the side-effect request.'
}

function Config([string]$Path,[string]$Method='GET',$Extractors=@(),$Headers=@(),$Body=@{type='NONE'},$Assertions=@(@{id='status';type='STATUS_CODE';expected=200})) {
 return @{request=@{method=$Method;path=$Path;query=@();headers=@($Headers);body=$Body};assertions=@($Assertions);extractors=@($Extractors);timeoutMs=15000}
}
function FlowCase([string]$Name,$Config) { return (Api POST "$script:base/cases" @{name=$Name;configSchemaVersion=1;config=$Config} 201).id }
function Plan([string]$Name,[string[]]$Ids,[bool]$Fast=$true) {
 return (Api POST "$script:base/plans" @{name=$Name;executionMode='ORDERED';failFast=$Fast;steps=@($Ids | ForEach-Object { @{caseId=$_;enabled=$true} })} 201).id
}
function SubmitPlan([string]$Id) { return Api POST "$script:base/runs" @{sourceType='PLAN';sourceId=$Id;environmentId=$script:environment} 202 ([guid]::NewGuid().ToString()) }
function Items([string]$Id) { return @((Api GET "$script:base/runs/$Id/items?size=100").items) }
function States([string]$Id) { return ((Items $Id | ForEach-Object state) -join ',') }
function Wait-Item([string]$Id,[int]$Ordinal,[string]$State) {
 for($i=0;$i -lt 150;$i++) { $item=@(Items $Id | Where-Object ordinal -EQ $Ordinal)[0]; if($item.state -eq $State){return}; Start-Sleep -Milliseconds 100 }
 throw 'Step did not reach expected state.'
}
$api=$null; $worker=$null; $fixture=$null
try {
 foreach($port in @($ApiPort,$TargetPort)) { if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Port $port is in use."} }
 $source=Join-Path $script:TestFlowRoot 'scripts/fixtures/DemoTarget.java'
 $fixture=Start-Child 'target' @('-Xms32m','-Xmx128m','--source','21',('"'+$source+'"'),[string]$TargetPort)
 $ready=$false;for($i=0;$i -lt 80;$i++){try{$null=Stats;$ready=$true;break}catch{Start-Sleep -Milliseconds 200}};Assert $ready 'Target did not become ready.'
 $api=Start-Api 'api-initial';Login
 $owner=(Api GET '/auth/me').id
 $project=(Api POST '/projects' @{name='有序计划 · 合成验证';ownerId=$owner} 201).id;$script:base="/projects/$project"
 $script:environment=(Api POST "$script:base/environments" @{name='有序合成目标';baseUrl=$target;variables=@{marker='before'}} 201).id
 $policy=(Api POST "$script:base/target-policies" @{scheme='http';host='127.0.0.1';port=$TargetPort;addressScope='LOOPBACK'} 201).id
 $login=FlowCase '合成登录' (Config '/flow/login' 'POST' @(@{name='token';path='$.data.token';secret=$true},@{name='quantity';path='$.data.quantity';secret=$false}))
 $headers=@(@{name='Authorization';value='Bearer ${run.token}'})
 $create=FlowCase '创建资源' (Config '/flow/orders' 'POST' @(@{name='orderId';path='$.data.id';secret=$false}) $headers @{type='JSON';json=@{quantity='${run.quantity}';marker='${env.marker}'}})
 $query=FlowCase '查询资源' (Config '/flow/orders/${run.orderId}' 'GET' @() $headers @{type='NONE'} @(@{id='status';type='STATUS_CODE';expected=200},@{id='id';type='JSON_EQUALS';path='$.data.id';expected='${run.orderId}'}))
 $flow=Plan '冻结有序计划' @($login,$create,$query,$query)
 $queued=SubmitPlan $flow
 $failure=FlowCase '失败生产者' (Config '/failed' 'GET' @(@{name='orderId';path='$.data.id';secret=$false}))
 $null=Api PATCH "$script:base/plans/$flow" @{version=0;steps=@(@{caseId=$failure;enabled=$true});failFast=$false}
 Stop-Child $api;$api=Start-Api 'api-restarted';Login;$worker=Start-Worker 'worker-initial'
 $done=Wait-Run $queued.id 'COMPLETED'
 Assert ($done.outcome -eq 'PASSED' -and (States $queued.id) -eq 'PASSED,PASSED,PASSED,PASSED') 'Frozen ordered flow failed or duplicate reference was merged.'
 Pass 'ordered-login-create-query-typed-values-duplicate-reference-and-api-restart'

 $current=SubmitPlan $flow;$null=Wait-Run $current.id 'COMPLETED';Assert ((States $current.id) -eq 'FAILED') 'Current configuration did not take effect.'
 $key=[guid]::NewGuid().ToString();$rerun=Api POST "$script:base/runs/$($queued.id)/reruns" @{} 202 $key
 $rerunDone=Wait-Run $rerun.id 'COMPLETED';Assert ($rerunDone.outcome -eq 'PASSED' -and $rerunDone.parentRunId -eq $queued.id) 'Snapshot rerun used mutable definitions.'
 $again=Api POST "$script:base/runs/$($queued.id)/reruns" @{} 200 $key;Assert ($again.id -eq $rerun.id) 'Rerun retry duplicated the run.'
 Pass 'original-snapshot-rerun-versus-current-configuration-and-idempotency'

 $parallelRuns=Plan '隔离的两个有序运行' @($login,$create,$query)
 $a=SubmitPlan $parallelRuns;$b=SubmitPlan $parallelRuns
 Assert ((Wait-Run $a.id 'COMPLETED').outcome -eq 'PASSED') 'First isolated context failed.'
 Assert ((Wait-Run $b.id 'COMPLETED').outcome -eq 'PASSED') 'Second context leaked variables.'
 # Two independent runs use the pre-existing bounded Worker pool; no parallel plan steps are enabled.
 Pass 'run-local-variable-contexts-do-not-cross-between-runs'

 foreach($runId in @($a.id,$b.id)) { foreach($item in (Items $runId)) {
   $detail=Api GET "$script:base/runs/$runId/items/$($item.id)"
   $serialized=$detail | ConvertTo-Json -Depth 30 -Compress
   Assert (-not $detail.bodyAvailable -and $serialized -notmatch 'synthetic-[0-9a-f-]{36}') 'Runtime secret entered a report.'
 } }
 Pass 'runtime-secret-taint-and-report-protection'

 $before=Count '/flow/orders'
 $fast=SubmitPlan (Plan '断言失败停止' @($login,$failure,$query) $true);$null=Wait-Run $fast.id 'COMPLETED'
 Assert ((States $fast.id) -eq 'PASSED,FAILED,SKIPPED') 'Fail-fast did not skip remaining items.'
 $continued=SubmitPlan (Plan '断言失败继续' @($login,$failure,$query) $false);$null=Wait-Run $continued.id 'COMPLETED'
 Assert ((States $continued.id) -eq 'PASSED,FAILED,ERROR') 'Failed producer published values or missing dependency was not ERROR.'
 Assert ((Count '/flow/orders') -eq $before) 'Missing dependency dispatched HTTP.'
 Pass 'assertion-failure-fail-fast-and-missing-runtime-variable-without-http'

 $missing=FlowCase '缺失提取' (Config '/flow/missing' 'GET' @(@{name='orderId';path='$.data.id';secret=$false}))
 $missingRun=SubmitPlan (Plan '提取失败继续' @($login,$missing,$query) $false);$null=Wait-Run $missingRun.id 'COMPLETED'
 Assert ((States $missingRun.id) -eq 'PASSED,ERROR,ERROR') 'Extraction failure incorrectly published a value.'
 Pass 'failed-extraction-publishes-no-value-and-dependency-errors'

 $slow=Case '副作用等待' '/mutate';$ok=Case '独立步骤' '/ok'
 $slowPlan=Plan '取消和故障边界' @($ok,$slow,$ok)
 $mutationBefore=(Stats).mutations;$cancelled=SubmitPlan $slowPlan;Wait-Mutations ($mutationBefore+1)
 $null=Api POST "$script:base/runs/$($cancelled.id)/cancel" @{} 202;$cancelDone=Wait-Run $cancelled.id 'CANCELLED'
 Assert ((States $cancelled.id) -eq 'PASSED,UNKNOWN,CANCELLED' -and $cancelDone.metrics.passed -eq 1) 'Cancellation lost earlier results or dispatched later steps.'
 Pass 'ordered-cancellation-retains-prior-results-and-unknown-current'

 $before=(Stats).mutations;$lost=SubmitPlan $slowPlan;Wait-Mutations ($before+1);Stop-Child $worker;$worker=$null
 $null=Wait-Run $lost.id 'INTERRUPTED';Assert ((States $lost.id) -eq 'PASSED,UNKNOWN,SKIPPED') 'Worker loss did not preserve ordered partial results.'
 $worker=Start-Worker 'worker-restarted';Start-Sleep -Seconds 3
 Assert ((Stats).mutations -eq ($before+1)) 'Worker restart automatically replayed an external side effect.'
 Pass 'ordered-worker-loss-preserves-results-and-never-resumes-automatically'

 $history=Api GET "$script:base/runs?sourceType=PLAN&sourceId=$slowPlan&state=INTERRUPTED&outcome=INCOMPLETE&environmentId=$script:environment&createdFrom=2020-01-01T00%3A00%3A00Z&createdTo=2099-01-01T00%3A00%3A00Z"
 Assert ($history.total -eq 1 -and $history.items[0].id -eq $lost.id) 'History filters/count do not match persisted runs.'
 Pass 'historical-filters-use-source-environment-state-outcome-and-time'

 $null=Api PATCH "$script:base/plans/$flow" @{version=1;status='ARCHIVED'}
 $null=Api PATCH "$script:base/cases/$login" @{version=0;status='ARCHIVED'}
 $null=Api PATCH "$script:base/environments/$script:environment" @{version=0;status='ARCHIVED'}
 $archived=Api POST "$script:base/runs/$($queued.id)/reruns" @{} 202 ([guid]::NewGuid().ToString())
 Assert ((Wait-Run $archived.id 'COMPLETED').outcome -eq 'PASSED') 'Archiving definitions destroyed original snapshot replay.'
 $null=Api PATCH "$script:base/target-policies/$policy" @{version=0;enabled=$false}
 $null=Api POST "$script:base/runs/$($queued.id)/reruns" @{} 403 ([guid]::NewGuid().ToString())
 Pass 'archived-definition-rerun-preserves-inputs-but-honors-revoked-target'

 @{Passed=$true;Count=$checks.Count;CheckedAt=(Get-Date).ToString('o');Scope='real API/Worker JAR + dedicated MySQL/Redis + synthetic ordered HTTP target'} | ConvertTo-Json | Set-Content (Join-Path $evidence 'summary.json') -Encoding utf8
 $checks | ForEach-Object { @{Check=$_;Passed=$true} } | ConvertTo-Json | Set-Content (Join-Path $evidence 'checks.json') -Encoding utf8
 Write-Host "All $($checks.Count) ordered plan independent-process checks passed. No load/performance claims."
} finally { foreach($child in $children){Stop-Child $child} }
