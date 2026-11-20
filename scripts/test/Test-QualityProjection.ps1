<#
.SYNOPSIS
验证Outbox、Kafka、消费者幂等和质量补齐。
.DESCRIPTION
需要JAR、独立MySQL/Redis及开发Kafka；自动创建独立测试主题。
.PARAMETER ApiPort
本工具连接或启动的API端口，按脚本默认值使用。
.PARAMETER TargetPort
本机合成HTTP目标端口，按脚本默认值使用。
.NOTES
会停止/恢复本项目Kafka并注入专用测试库故障，结果：.local/verification/quality-projection。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([int]$ApiPort=18080,[int]$TargetPort=18088)
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
$ErrorActionPreference='Stop'
$config=Get-TestFlowConfig
$script:qualityTopic="testflow-test.run-finalized.$([guid]::NewGuid().ToString('N'))"
$script:qualityGroup="testflow-test-quality-$([guid]::NewGuid().ToString('N'))"
$evidence=Join-Path $script:TestFlowRoot '.local/verification/quality-projection'
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
    $env:TF_QUALITY_ENABLED='true'
    $env:TF_QUALITY_TOPIC=$script:qualityTopic
    $env:TF_QUALITY_GROUP_ID=$script:qualityGroup
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

function Kafka([string[]]$Command) {
 Set-TestFlowDockerEnvironment
 & docker compose -f (Join-Path $script:TestFlowRoot 'compose.yml') @Command | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Project Kafka command failed.'}
}
function Sql([string]$Statement) {$rows=@(Invoke-TestFlowSql -Sql $Statement);if($rows.Count){return $rows[-1]}}
function Wait-Quality([int]$Count,[int]$Seconds=90) {
 $until=[DateTime]::UtcNow.AddSeconds($Seconds)
 while([DateTime]::UtcNow -lt $until){$q=Api GET "$script:base/quality";if($q.status.projectedRuns -eq $Count -and $q.status.missingRuns -eq 0){return $q};Start-Sleep -Milliseconds 300}
 throw "Quality projection did not reach $Count authoritative terminal runs."
}
function Wait-Failure([string]$EventId='') {
 for($i=0;$i -lt 120;$i++){
  if($EventId){$list=(Api GET "$script:base/quality/failures").items;$found=@($list | Where-Object { $_.eventId -eq $EventId -and $_.status -eq 'OPEN' })}
  else{$list=Api GET '/admin/quality/failures/unassigned';$found=@($list | Where-Object status -EQ 'OPEN')}
  if($found.Count){return $found[0]};Start-Sleep -Milliseconds 300
 };throw 'Consumer did not persist the failed record.'
}
function Wait-Resolved([string]$Id) {
 for($n=0;$n -lt 120;$n++){if((Sql "SELECT status FROM testflow_test.tf_quality_failure WHERE id=$Id;") -eq 'RESOLVED'){return};Start-Sleep -Milliseconds 250}
 throw 'Trusted replay did not resolve the parked record.'
}
function Publish([string]$Key,[string]$Payload) {
 Set-TestFlowDockerEnvironment
 "$Key|$Payload" | & docker compose -f (Join-Path $script:TestFlowRoot 'compose.yml') exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic $script:qualityTopic --property parse.key=true --property 'key.separator=|' 2>> (Join-Path $evidence 'producer.log')
 if($LASTEXITCODE -ne 0){throw 'Synthetic Kafka record injection failed.'}
}
function Wait-Consumed {
 for($n=0;$n -lt 20;$n++){
  Set-TestFlowDockerEnvironment
  $lines=@(& docker compose -f (Join-Path $script:TestFlowRoot 'compose.yml') exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --group $script:qualityGroup --describe 2>> (Join-Path $evidence 'group.err.log'))
  $lines | Set-Content (Join-Path $evidence 'consumer-offsets.txt')
  foreach($line in $lines){$parts=$line.Trim() -split '\s+';if($parts.Count -ge 6 -and $parts[1] -eq $script:qualityTopic -and $parts[3] -match '^\d+$' -and $parts[3] -eq $parts[4]){return}}
  Start-Sleep -Milliseconds 500
 };throw 'Kafka consumer offset did not reach the topic end.'
}
function Reset-Offsets {
 for($n=0;$n -lt 25;$n++){
  Set-TestFlowDockerEnvironment
  $lines=@(& docker compose -f (Join-Path $script:TestFlowRoot 'compose.yml') exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --group $script:qualityGroup --topic $script:qualityTopic --reset-offsets --to-earliest --execute 2>> (Join-Path $evidence 'offset-reset.err.log'))
  $lines | Set-Content (Join-Path $evidence 'offset-reset.txt')
  if(($lines -join "`n") -match ([regex]::Escape($script:qualityTopic)+'\s+0\s+0(?:\s|$)')){return}
  Start-Sleep -Seconds 2
 };throw 'Consumer group did not become inactive for verified offset reset.'
}
$api=$null;$worker=$null;$fixture=$null;$triggerCreated=$false
try {
 foreach($port in @($ApiPort,$TargetPort)){if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Port $port is in use."}}
 # Each invocation owns its topic/group: historical poison records must not affect a fresh test.
 Kafka @('--profile','quality','up','-d','--wait','kafka')
 Set-TestFlowDockerEnvironment
 & docker compose -f (Join-Path $script:TestFlowRoot 'compose.yml') exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --topic $script:qualityTopic --partitions 1 --replication-factor 1 --config retention.ms=604800000 --config retention.bytes=268435456 --config max.message.bytes=32768 | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Isolated test topic initialization failed.'}
 @{Topic=$script:qualityTopic;Group=$script:qualityGroup} | ConvertTo-Json | Set-Content (Join-Path $evidence 'topic.json')
 Kafka @('--profile','quality','stop','kafka')
 $source=Join-Path $script:TestFlowRoot 'scripts/fixtures/DemoTarget.java'
 $fixture=Start-Child 'target' @('-Xms32m','-Xmx128m','--source','21',('"'+$source+'"'),[string]$TargetPort)
 for($i=0;$i -lt 80;$i++){try{$null=Stats;break}catch{Start-Sleep -Milliseconds 200}}
 $api=Start-Api 'api-broker-down';Login;$owner=(Api GET '/auth/me').id
 $project=(Api POST '/projects' @{name='质量统计恢复 · 合成验证';ownerId=$owner} 201).id;$script:base="/projects/$project"
 $script:environment=(Api POST "$script:base/environments" @{name='质量合成目标';baseUrl=$target;variables=@{}} 201).id
 $null=Api POST "$script:base/target-policies" @{scheme='http';host='127.0.0.1';port=$TargetPort;addressScope='LOOPBACK'} 201
 $worker=Start-Worker 'worker'
 $ok=Case '质量正常请求' '/ok';$bad=FlowCase '质量断言失败' (Config '/failed')
 $p=Plan '有序统计' @($ok,$ok);$r1=SubmitPlan $p;$null=Wait-Run $r1.id 'COMPLETED'
 $ip=Independent '独立统计' @($ok,$ok,$ok) 2;$r2=SubmitPlan $ip;$null=Wait-Run $r2.id 'COMPLETED'
 $q=Api GET "$script:base/quality";Assert ($q.status.state -eq 'LAGGING' -and $q.status.missingRuns -eq 2 -and $null -eq $q.metrics.passRate) 'Missing projections were presented as complete zero-failure statistics.'
 Assert ((Sql "SELECT COUNT(*) FROM testflow_test.tf_outbox_event WHERE project_id=$project;") -eq '2') 'Run finalization did not persist Outbox while Kafka was down.'
 $null=Api GET "$script:base/runs/$($r1.id)/items";Pass 'broker-down-api-start-ordered-independent-finalization-and-report-available'
 Stop-Child $api;$api=Start-Api 'api-restarted-broker-down';Login
 Assert ((Api GET "$script:base/runs/$($r2.id)").metrics.passed -eq 3) 'Broker outage or API restart lost the original report.'
 Kafka @('--profile','quality','up','-d','--wait','kafka')
 $q=Wait-Quality 2;Assert ($q.metrics.total -eq 5 -and $q.metrics.passRate -eq 100) 'Backlog recovery produced wrong weighted counts.'
 Pass 'durable-backlog-recovers-after-api-restart-and-broker-return'

 $event=Sql "SELECT event_id FROM testflow_test.tf_outbox_event WHERE run_id=$($r1.id);"
 $payload=Sql "SELECT CAST(payload AS CHAR) FROM testflow_test.tf_outbox_event WHERE event_id='$event';"
 $before=Count '/ok';Publish $r1.id $payload;Publish $r1.id $payload
 Wait-Consumed;$q=Wait-Quality 2
 Assert ($q.metrics.total -eq 5 -and (Count '/ok') -eq $before) 'Duplicate messages repeated statistics or HTTP.'
 Pass 'duplicate-kafka-delivery-is-idempotent-and-never-replays-http'

 Stop-Child $api;$api=$null
 Reset-Offsets
 $api=Start-Api 'api-offset-replay';Login;$q=Wait-Quality 2;Wait-Consumed
 Assert ((Sql "SELECT COUNT(*) FROM testflow_test.tf_quality_run_fact WHERE project_id=$project;") -eq '2' -and (Count '/ok') -eq $before) 'Consumer restart/redelivery duplicated facts or HTTP.'
 Pass 'consumer-restart-and-offset-redelivery-preserve-unique-facts'

 $null=Sql "CREATE TRIGGER testflow_test.stage5_projection_fault BEFORE INSERT ON testflow_test.tf_quality_run_fact FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC_PROJECTION_UNAVAILABLE';";$triggerCreated=$true
 $r3=Submit $ok;$null=Wait-Run $r3.id 'COMPLETED';$event3=Sql "SELECT event_id FROM testflow_test.tf_outbox_event WHERE run_id=$($r3.id);"
 $failure=Wait-Failure $event3;Assert ((Api GET "$script:base/runs/$($r3.id)").metrics.passed -eq 1) 'Consumer database failure blocked original execution.'
 Assert ((Api GET "$script:base/quality").status.missingRuns -eq 1) 'Failed projection was silently counted.'
 $null=Sql 'DROP TRIGGER testflow_test.stage5_projection_fault;';$triggerCreated=$false
 $before=Count '/ok';$null=Api POST "$script:base/quality/failures/$($failure.id)/replay" @{}
 $q=Wait-Quality 3;Assert ((Sql "SELECT status FROM testflow_test.tf_quality_failure WHERE id=$($failure.id);") -eq 'RESOLVED' -and (Count '/ok') -eq $before) 'Trusted event replay failed to repair without HTTP.'
 Pass 'consumer-database-failure-bounded-retry-durable-parking-and-trusted-replay'

 $canary='SYNTHETIC_UNTRUSTED_BODY_STAGE5';Publish 'invalid' ('{"eventId":"'+[guid]::NewGuid().ToString()+'","secret":"'+$canary+'"}')
 $unassigned=Wait-Failure
 Assert ($null -eq $unassigned.PSObject.Properties['payload']) 'Failure diagnostics exposed raw record data.'
 $r4=Submit $ok;$null=Wait-Run $r4.id 'COMPLETED';$null=Wait-Quality 4
 $leaked=@(Get-ChildItem -LiteralPath $evidence -Filter 'api*.log' | Select-String -SimpleMatch $canary)
 Assert ($leaked.Count -eq 0) 'Framework logging exposed an untrusted message body.'
 Pass 'malformed-record-isolated-without-secret-logging-or-partition-poisoning'

 $tampered=$payload | ConvertFrom-Json;$tampered.passed=0;$tampered.failed=2;$tampered.outcome='FAILED'
 Publish $r1.id ($tampered | ConvertTo-Json -Depth 10 -Compress)
 $conflict=Wait-Failure $event;$q=Wait-Quality 4;Assert ($q.metrics.passed -eq 7 -and $q.metrics.failed -eq 0) 'Untrusted event overwrote authoritative facts.'
 $null=Api POST "$script:base/quality/failures/$($conflict.id)/replay" @{};Wait-Resolved $conflict.id;Wait-Consumed
 Assert ((Sql "SELECT status FROM testflow_test.tf_quality_failure WHERE id=$($conflict.id);") -eq 'RESOLVED') 'Conflict was not resolved from the original stored event.'
 Pass 'conflicting-event-rejected-and-replay-uses-only-authoritative-outbox'

 $before=Count '/ok';$null=Sql "DELETE FROM testflow_test.tf_quality_run_fact WHERE run_id=$($r2.id);"
 Assert ((Api GET "$script:base/quality").status.missingRuns -eq 1) 'Missing fact was not detected.'
 $repair=Api POST "$script:base/quality/reconcile" @{limit=50};Assert ($repair.requeuedEvents -eq 1) 'Published event was not requeued for missing fact.'
 $q=Wait-Quality 4;Assert ($q.metrics.total -eq 7 -and (Count '/ok') -eq $before) 'Fact reconstruction changed counts or executed HTTP.'
 Pass 'missing-fact-reconciliation-restores-source-counts-without-new-run'

 $null=Sql "UPDATE testflow_test.tf_outbox_event SET status='PUBLISHING',claim_token=UUID(),lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE event_id='$event';"
 for($i=0;$i -lt 100;$i++){if((Sql "SELECT status FROM testflow_test.tf_outbox_event WHERE event_id='$event';") -eq 'PUBLISHED'){break};Start-Sleep -Milliseconds 200}
 Assert ((Sql "SELECT status FROM testflow_test.tf_outbox_event WHERE event_id='$event';") -eq 'PUBLISHED' -and (Wait-Quality 4).metrics.total -eq 7) 'Expired publisher lease did not recover idempotently.'
 Pass 'expired-publisher-claim-recovers-after-ambiguous-delivery'

 $fp=Plan '失败停止统计' @($bad,$ok,$ok);$fr=SubmitPlan $fp;$fd=Wait-Run $fr.id 'COMPLETED'
 Assert ($fd.metrics.failed -eq 1 -and $fd.metrics.skipped -eq 2) 'Failure-stop fixture did not produce the intended classification.'
 Stop-Child $worker;$worker=$null;$cancel=Submit $ok;$null=Api POST "$script:base/runs/$($cancel.id)/cancel" @{} 200;$null=Wait-Run $cancel.id 'CANCELLED'
 $timeout=Submit $ok;$null=Sql "UPDATE testflow_test.tf_test_run SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=$($timeout.id);";$null=Wait-Run $timeout.id 'TIMED_OUT'
 $worker=Start-Worker 'worker-interruption';$gp=GatedPlan 'quality-lost' 4 2;$lost=SubmitPlan $gp;Wait-Entered 'quality-lost' 2;Stop-Child $worker;$worker=$null;$null=Wait-Run $lost.id 'INTERRUPTED';Drain 'quality-lost'
 $q=Wait-Quality 8;Assert ($q.metrics.runs -eq 5 -and $q.metrics.failed -eq 1 -and $q.metrics.skipped -eq 2 -and $q.status.incompleteRuns -eq 3) 'Default statistics mixed incomplete runs with completed regression.'
 $all=Api GET "$script:base/quality?includeIncomplete=true";Assert ($all.metrics.runs -eq 8 -and $all.metrics.unknown -eq 2 -and $all.metrics.cancelled -eq 1) 'Opt-in incomplete classification was incorrect.'
 Pass 'fail-fast-cancel-timeout-worker-loss-classification-stable-across-async-order'
 $filtered=Api GET "$script:base/quality?sourceType=PLAN&sourceId=$p&environmentId=$script:environment"
 Assert ($filtered.metrics.runs -eq 1 -and $filtered.recent[0].runId -eq $r1.id -and $filtered.days.Count -eq 1) 'Source/environment/day filters or report references were inconsistent.'
 $null=Api GET "$script:base/runs/$($filtered.recent[0].runId)";Pass 'quality-filter-freshness-and-original-report-drilldown'

 @{Passed=$true;Count=$checks.Count;CheckedAt=(Get-Date).ToString('o');Scope='real Kafka, API and Worker JARs; dedicated MySQL/Redis; synthetic HTTP target'} | ConvertTo-Json | Set-Content (Join-Path $evidence 'summary.json') -Encoding utf8
 $checks | ForEach-Object { @{Check=$_;Passed=$true} } | ConvertTo-Json | Set-Content (Join-Path $evidence 'checks.json') -Encoding utf8
 Write-Host "All $($checks.Count) quality projection independent-process checks passed."
}finally{
 if($triggerCreated){$null=Invoke-TestFlowSql -Sql 'DROP TRIGGER IF EXISTS testflow_test.stage5_projection_fault;'}
 foreach($child in $children){Stop-Child $child}
 Kafka @('--profile','quality','up','-d','--wait','kafka')
}

