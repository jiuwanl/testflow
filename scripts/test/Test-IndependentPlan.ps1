<#
.SYNOPSIS
验证独立计划并发预算、取消和结果一致性。
.DESCRIPTION
需要已构建JAR、开发依赖和独立测试库；自动准备受控并发HTTP目标。
.PARAMETER ApiPort
本工具连接或启动的API端口，按脚本默认值使用。
.PARAMETER TargetPort
本机合成HTTP目标端口，按脚本默认值使用。
.NOTES
会创建合成记录并结束自有测试进程；结果：.local/verification/independent-plan。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([int]$ApiPort=18080,[int]$TargetPort=18088)
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
$ErrorActionPreference='Stop'
$config=Get-TestFlowConfig
$evidence=Join-Path $script:TestFlowRoot '.local/verification/independent-plan'
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

function ParallelStats {return Invoke-RestMethod "$target/parallel/stats" -TimeoutSec 3}
function FixtureGroup([string]$Name) {return (ParallelStats).groups.PSObject.Properties[$Name].Value}
function Wait-Entered([string]$Name,[int]$Expected) {
 for($i=0;$i -lt 150;$i++){$p=(ParallelStats).groups.PSObject.Properties[$Name];if($null -ne $p -and $p.Value.entered -ge $Expected){return};Start-Sleep -Milliseconds 100}
 throw "Synthetic group $Name did not enter $Expected requests."
}
function Release([string]$Name,[string]$Key='') {
 $url="$target/parallel/release?group=$Name";if($Key){$url+="&key=$Key"};$null=Invoke-RestMethod $url
}
function Drain([string]$Name) {
 Release $Name
 for($i=0;$i -lt 100;$i++){if((FixtureGroup $Name).active -eq 0){return};Start-Sleep -Milliseconds 100};throw 'Fixture did not drain.'
}
function ParallelCase([string]$Name,[string]$Group,[string]$Item,[int]$Delay=100,[string]$Gate='',[int]$Status=200,[int]$Timeout=15000) {
 $query=@(@{name='group';value=$Group},@{name='item';value=$Item},@{name='ms';value=[string]$Delay},@{name='status';value=[string]$Status});if($Gate){$query+=@{name='gate';value=$Gate}}
 return (Api POST "$script:base/cases" @{name=$Name;configSchemaVersion=1;config=@{request=@{method='POST';path='/parallel/work';query=$query;headers=@();body=@{type='NONE'}};assertions=@(@{id='status';type='STATUS_CODE';expected=200});extractors=@();timeoutMs=$Timeout}} 201).id
}
function Independent([string]$Name,[string[]]$Ids,[int]$Limit=2,[bool]$Fast=$false){return (Api POST "$script:base/plans" @{name=$Name;executionMode='INDEPENDENT';maxConcurrency=$Limit;failFast=$Fast;steps=@($Ids | ForEach-Object { @{caseId=$_;enabled=$true} })} 201).id}
function GatedPlan([string]$Group,[int]$Total,[int]$Limit){$ids=@(1..$Total | ForEach-Object {ParallelCase "$Group-$_" $Group "$_" 100 'hold'});return Independent $Group $ids $Limit}
$api=$null;$worker=$null;$fixture=$null
try {
 foreach($port in @($ApiPort,$TargetPort)){if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Port $port is in use."}}
 $source=Join-Path $script:TestFlowRoot 'scripts/fixtures/DemoTarget.java'
 $fixture=Start-Child 'target' @('-Xms32m','-Xmx128m','--source','21',('"'+$source+'"'),[string]$TargetPort)
 $ready=$false;for($i=0;$i -lt 80;$i++){try{$null=Stats;$ready=$true;break}catch{Start-Sleep -Milliseconds 200}};Assert $ready 'Target did not become ready.'
 $api=Start-Api 'api';Login;$owner=(Api GET '/auth/me').id
 $project=(Api POST '/projects' @{name='独立计划并发 · 合成验证';ownerId=$owner} 201).id;$script:base="/projects/$project"
 $script:environment=(Api POST "$script:base/environments" @{name='并行合成目标';baseUrl=$target;variables=@{}} 201).id
 $policy=(Api POST "$script:base/target-policies" @{scheme='http';host='127.0.0.1';port=$TargetPort;addressScope='LOOPBACK'} 201).id
 $dependent=FlowCase '跨步骤依赖' (Config '/next/${run.id}')
 $null=Api POST "$script:base/plans" @{name='拒绝独立依赖';executionMode='INDEPENDENT';steps=@(@{caseId=$dependent;enabled=$false})} 400
 $ordered=Plan '依赖有序' @($dependent)
 $null=Api PATCH "$script:base/plans/$ordered" @{version=0;executionMode='INDEPENDENT'} 400
 Pass 'independent-save-and-mode-switch-reject-cross-step-dependencies'

 $slow=ParallelCase '先派发后完成' 'order' 'slow' 100 'slow'
 $fast=ParallelCase '后派发先完成' 'order' 'fast' 50
 $third=ParallelCase '第三步' 'order' 'third' 50
 $plan=Independent '冻结独立并行' @($slow,$fast,$third) 2
 $queued=SubmitPlan $plan
 $null=Api PATCH "$script:base/plans/$plan" @{version=0;executionMode='ORDERED';maxConcurrency=1}
 $worker=Start-Worker 'worker-initial';Wait-Entered 'order' 3
 Wait-Item $queued.id 2 'PASSED';Wait-Item $queued.id 3 'PASSED'
 Assert ((Api GET "$script:base/runs/$($queued.id)").state -eq 'RUNNING') 'Run finalized while first request was still active.'
 Assert ((FixtureGroup 'order').peak -eq 2) 'Independent requests did not overlap or exceeded per-run budget.'
 Release 'order';$done=Wait-Run $queued.id 'COMPLETED'
 Assert ($done.executionMode -eq 'INDEPENDENT' -and $done.maxConcurrency -eq 2 -and $done.metrics.passed -eq 3) 'Frozen execution policy was not used.'
 Assert ((FixtureGroup 'order').completions.fast -lt (FixtureGroup 'order').completions.slow) 'Fixture did not observe out-of-order completion.'
 Pass 'frozen-independent-mode-per-run-limit-out-of-order-and-inflight-progress'
 $rerun=Api POST "$script:base/runs/$($queued.id)/reruns" @{} 202 ([guid]::NewGuid().ToString())
 $repeat=Wait-Run $rerun.id 'COMPLETED';Assert ($repeat.executionMode -eq 'INDEPENDENT' -and $repeat.maxConcurrency -eq 2 -and $repeat.parentRunId -eq $queued.id -and $repeat.metrics.passed -eq 3) 'Snapshot rerun changed mode or concurrency.'
 Pass 'original-snapshot-rerun-keeps-independent-policy-after-plan-switch'

 $pA=GatedPlan 'budget-a' 5 3;$pB=GatedPlan 'budget-b' 5 3;$pC=GatedPlan 'budget-c' 2 2
 $a=SubmitPlan $pA;$b=SubmitPlan $pB;$c=SubmitPlan $pC
 for($i=0;$i -lt 100;$i++){if((ParallelStats).active -eq 4){break};Start-Sleep -Milliseconds 100}
 Assert ((ParallelStats).active -eq 4) 'Worker global resource budget was not exercised.'
 Assert ((FixtureGroup 'budget-a').peak -le 3 -and (FixtureGroup 'budget-b').peak -le 3) 'Run budget exceeded.'
 Assert ((Api GET "$script:base/runs/$($c.id)").state -eq 'QUEUED') 'Worker exceeded its two-run ownership budget.'
 Release 'budget-a';Release 'budget-b'
 Assert ((Wait-Run $a.id 'COMPLETED').metrics.passed -eq 5) 'Backpressure converted available steps to errors.'
 Assert ((Wait-Run $b.id 'COMPLETED').metrics.passed -eq 5) 'Second run failed under shared budget.'
 Wait-Entered 'budget-c' 2;Release 'budget-c';Assert ((Wait-Run $c.id 'COMPLETED').metrics.passed -eq 2) 'Persisted queued run was lost.'
 Assert ((ParallelStats).peak -eq 4) 'Synthetic target observed global concurrency above 4.'
 Pass 'worker-global-four-per-run-three-two-owned-runs-bounded-backpressure'

 $long=ParallelCase '已在执行' 'fail-fast' 'long' 100 'slow'
 $bad=ParallelCase '断言失败' 'fail-fast' 'bad' 10 'fast' 500
 $tail=ParallelCase '尚未派发' 'fail-fast' 'tail'
 $p=Independent '并行失败停止' @($long,$bad,$tail,$tail) 2 $true;$r=SubmitPlan $p;Wait-Entered 'fail-fast' 2
 Release 'fail-fast' 'fast';Wait-Item $r.id 2 'FAILED'
 Assert ((States $r.id) -eq 'RUNNING,FAILED,SKIPPED,SKIPPED') 'Fail-fast discarded inflight work or dispatched pending work.'
 Assert ((Api GET "$script:base/runs/$($r.id)").state -eq 'RUNNING') 'Fail-fast finalized too early.'
 Release 'fail-fast' 'slow';$final=Wait-Run $r.id 'COMPLETED'
 Assert ($final.metrics.passed -eq 1 -and $final.metrics.failed -eq 1 -and $final.metrics.skipped -eq 2 -and (FixtureGroup 'fail-fast').entered -eq 2) 'Fail-fast counts or HTTP dispatch were inconsistent.'
 Pass 'parallel-fail-fast-stops-pending-retains-inflight-known-results'

 $p=GatedPlan 'cancel-all' 6 4;$r=SubmitPlan $p;Wait-Entered 'cancel-all' 4
 $null=Api POST "$script:base/runs/$($r.id)/cancel" @{} 202
 $final=Wait-Run $r.id 'CANCELLED'
 Assert ($final.metrics.unknown -eq 4 -and $final.metrics.cancelled -eq 2 -and $final.metrics.progress -eq 100) 'Cancellation did not settle all parallel items honestly.'
 Drain 'cancel-all';Assert ((FixtureGroup 'cancel-all').entered -eq 4) 'Cancelled pending steps were dispatched.'
 Pass 'parallel-cancel-aborts-all-active-requests-and-cancels-pending'

 $timeout=ParallelCase '请求时限' 'timeouts' 'timeout' 1000 '' 200 250
 $ok=ParallelCase '不受影响' 'timeouts' 'ok' 80
 $p=Independent '超时继续' @($timeout,$ok,$ok) 2 $false;$r=SubmitPlan $p;$final=Wait-Run $r.id 'COMPLETED'
 Assert ($final.metrics.errors -eq 1 -and $final.metrics.passed -eq 2 -and (Items $r.id)[0].errorCode -eq 'REQUEST_TIMEOUT') 'Parallel request timeout corrupted unrelated work.'
 Drain 'timeouts';Pass 'request-timeout-isolated-and-continue-policy-preserved'

 $p=GatedPlan 'deadline' 4 2;$r=SubmitPlan $p;Wait-Entered 'deadline' 2
 $null=Invoke-TestFlowSql -Sql "UPDATE testflow_test.tf_test_run SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=$($r.id);"
 $final=Wait-Run $r.id 'TIMED_OUT';Assert ($final.metrics.unknown -eq 2 -and $final.metrics.skipped -eq 2) 'Runtime deadline failed to fence concurrent writers.'
 Drain 'deadline';Start-Sleep -Milliseconds 500
 Assert ((Api GET "$script:base/runs/$($r.id)").state -eq 'TIMED_OUT') 'Late results reopened the timed-out run.'
 Pass 'parallel-run-deadline-fences-all-inflight-and-late-results'

 $p=GatedPlan 'lost-worker' 4 2;$r=SubmitPlan $p;Wait-Entered 'lost-worker' 2
 Stop-Child $worker;$worker=$null;$final=Wait-Run $r.id 'INTERRUPTED'
 Assert ($final.metrics.unknown -eq 2 -and $final.metrics.skipped -eq 2) 'Worker loss failed to preserve all uncertain steps.'
 Drain 'lost-worker';$worker=Start-Worker 'worker-restarted';Start-Sleep -Seconds 3
 Assert ((FixtureGroup 'lost-worker').entered -eq 2 -and (Api GET "$script:base/runs/$($r.id)").state -eq 'INTERRUPTED') 'Restart replayed interrupted parallel side effects.'
 Pass 'parallel-worker-kill-lease-expiry-no-auto-replay'

 Stop-Child $worker;$worker=$null;$p=GatedPlan 'revoked' 4 2;$r=SubmitPlan $p
 $null=Api PATCH "$script:base/target-policies/$policy" @{version=0;enabled=$false}
 $worker=Start-Worker 'worker-revoked';$final=Wait-Run $r.id 'INTERRUPTED'
 Assert ($final.outcome -eq 'INCOMPLETE') 'Revoked authorization was bypassed.'
 Assert ($null -eq (ParallelStats).groups.PSObject.Properties['revoked']) 'Revoked target received an HTTP request.'
 Pass 'parallel-execution-still-enforces-current-target-authorization'

 @{Passed=$true;Count=$checks.Count;CheckedAt=(Get-Date).ToString('o');Scope='real API/Worker JAR; dedicated MySQL/Redis; gate-controlled synthetic HTTP concurrency'} | ConvertTo-Json | Set-Content (Join-Path $evidence 'summary.json') -Encoding utf8
 $checks | ForEach-Object { @{Check=$_;Passed=$true} } | ConvertTo-Json | Set-Content (Join-Path $evidence 'checks.json') -Encoding utf8
 ParallelStats | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $evidence 'concurrency.json') -Encoding utf8
 Write-Host "All $($checks.Count) independent plan process checks passed. Observed limits are not throughput benchmarks."
}finally{foreach($child in $children){Stop-Child $child}}
