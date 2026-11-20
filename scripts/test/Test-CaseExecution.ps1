<#
.SYNOPSIS
验证单用例持久任务、快照、幂等和故障处理。
.DESCRIPTION
需要已构建JAR、开发依赖及专用testflow_test；自动启动测试API、Worker和合成HTTP目标。
.PARAMETER ApiPort
本工具连接或启动的API端口，按脚本默认值使用。
.PARAMETER TargetPort
本机合成HTTP目标端口，按脚本默认值使用。
.NOTES
会创建测试数据并临时暂停/恢复本项目MySQL；结果：.local/verification/case-execution。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([int]$ApiPort=18080,[int]$TargetPort=18088)
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
$ErrorActionPreference='Stop'
$config=Get-TestFlowConfig
$evidence=Join-Path $script:TestFlowRoot '.local/verification/case-execution'
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
    return (Response-Json $response).data
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

$api=$null; $worker=$null; $fixture=$null
try {
    # Reject collisions rather than terminating another process or reusing an unknown listener.
    foreach ($port in @($ApiPort,$TargetPort)) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is already in use." }
    }
    $source=Join-Path $script:TestFlowRoot 'scripts/fixtures/DemoTarget.java'
    $fixture=Start-Child 'target' @('-Xms32m','-Xmx128m','--source','21',('"'+$source+'"'),[string]$TargetPort)
    $ready=$false
    for ($i=0;$i -lt 80;$i++) { try { $null=Stats; $ready=$true; break } catch { Start-Sleep -Milliseconds 200 } }
    Assert $ready 'Synthetic target did not become ready.'
    $api=Start-Api 'api-initial'; Login
    $owner=(Api GET '/auth/me').id
    $project=(Api POST '/projects' @{name='单用例执行 · 合成验证';ownerId=$owner} 201).id
    $script:base="/projects/$project"
    $script:environment=(Api POST "$script:base/environments" @{name='合成本机目标';baseUrl=$target;variables=@{marker='before'}} 201).id
    $approvedTargetPolicy=(Api POST "$script:base/target-policies" @{scheme='http';host='127.0.0.1';port=$TargetPort;addressScope='LOOPBACK'} 201).id

    $case=Case '冻结配置与重启领取' '/ok'
    $key=[guid]::NewGuid().ToString()
    $queued=Submit $case $script:environment $key
    Assert ((Count '/ok') -eq 0) 'API unexpectedly contacted the target before a Worker existed.'
    $null=Api PATCH "$script:base/cases/$case" @{version=0;config=@{request=@{method='GET';path='/failed';query=@();headers=@();body=@{type='NONE'}};assertions=@(@{id='status';type='STATUS_CODE';expected=200});extractors=@();timeoutMs=15000}}
    $replay=Api POST "$script:base/runs" @{sourceType='CASE';sourceId=$case;environmentId=$script:environment} 200 $key
    Assert ($replay.id -eq $queued.id) 'Idempotent replay created another run.'
    Stop-Child $api
    $api=Start-Api 'api-restarted'; Login
    $worker=Start-Worker 'worker-initial'
    $completed=Wait-Run $queued.id 'COMPLETED'
    Assert ($completed.outcome -eq 'PASSED' -and (Count '/ok') -eq 1 -and (Count '/failed') -eq 0) 'Frozen queued task was not executed exactly once after API restart.'
    Pass 'durable-queue-after-api-restart-and-frozen-case-and-idempotent-submit'

    $failed=Submit (Case '断言失败' '/failed')
    Assert ((Wait-Run $failed.id 'COMPLETED').outcome -eq 'FAILED') 'Assertion failure was reported as passing.'
    Pass 'actual-assertion-failure'
    $timeout=Submit (Case '请求总超时' '/stream' 600)
    $null=Wait-Run $timeout.id 'COMPLETED'
    $item=(Api GET "$script:base/runs/$($timeout.id)/items").items[0]
    Assert ($item.state -eq 'ERROR' -and $item.errorCode -eq 'REQUEST_TIMEOUT') 'Active stream bypassed the total deadline.'
    Pass 'actual-streaming-total-timeout'
    $large=Submit (Case '解压后响应上限' '/large')
    $null=Wait-Run $large.id 'COMPLETED'
    $item=(Api GET "$script:base/runs/$($large.id)/items").items[0]
    Assert ($item.state -eq 'ERROR' -and $item.errorCode -eq 'RESPONSE_TOO_LARGE') 'Gzip response limit was not enforced.'
    Pass 'actual-decompressed-response-limit'
    $redirect=Submit (Case '禁止重定向' '/redirect')
    $null=Wait-Run $redirect.id 'COMPLETED'
    Assert ((Count '/followed') -eq 0) 'Redirect contacted another target.'
    $dropped=Submit (Case '禁止自动重试' '/drop')
    $null=Wait-Run $dropped.id 'COMPLETED'
    Assert ((Count '/drop') -eq 1) 'The HTTP request was automatically retried.'
    Pass 'actual-no-redirect-and-no-http-retry'

    $privateValue='synthetic-'+[guid]::NewGuid().ToString()
    $privateEnv=(Api POST "$script:base/environments" @{name='合成敏感环境';baseUrl=$target;variables=@{};secretUpdates=@(@{name='token';operation='SET';value=$privateValue})} 201).id
    $privateCase=(Api POST "$script:base/cases" @{name='敏感请求报告保护';configSchemaVersion=1;config=@{request=@{method='GET';path='/echo';query=@();headers=@(@{name='Authorization';value='Bearer ${env.token}'});body=@{type='NONE'}};assertions=@(@{id='status';type='STATUS_CODE';expected=200});extractors=@();timeoutMs=15000}} 201).id
    $privateRun=Submit $privateCase $privateEnv
    $null=Wait-Run $privateRun.id 'COMPLETED'
    $privateItem=(Api GET "$script:base/runs/$($privateRun.id)/items").items[0]
    $detail=Api GET "$script:base/runs/$($privateRun.id)/items/$($privateItem.id)"
    Assert (-not $detail.bodyAvailable -and -not (($detail | ConvertTo-Json -Depth 30).Contains($privateValue))) 'Sensitive request data appeared in the persisted report.'
    Pass 'actual-secret-bearing-report-protection'

    $mismatchedEnv=(Api POST "$script:base/environments" @{name='合成地址范围拒绝';baseUrl="http://localhost:$TargetPort";variables=@{}} 201).id
    $null=Api POST "$script:base/target-policies" @{scheme='http';host='localhost';port=$TargetPort;addressScope='PUBLIC'} 201
    $blocked=Submit (Case '解析地址不符合授权范围' '/blocked-before-connect') $mismatchedEnv
    $null=Wait-Run $blocked.id 'COMPLETED'
    $blockedItem=(Api GET "$script:base/runs/$($blocked.id)/items").items[0]
    Assert ($blockedItem.errorCode -eq 'TARGET_ADDRESS_BLOCKED' -and (Count '/blocked-before-connect') -eq 0) 'Worker connected to an address outside its authorized scope.'
    Pass 'actual-dns-address-scope-refusal-before-connect'

    Stop-Child $worker
    $revoked=Submit (Case '排队后撤销目标授权' '/revoked-before-dispatch')
    $null=Api PATCH "$script:base/target-policies/$approvedTargetPolicy" @{version=0;enabled=$false}
    $worker=Start-Worker 'worker-revocation'
    $null=Wait-Run $revoked.id 'INTERRUPTED'
    $revokedItem=(Api GET "$script:base/runs/$($revoked.id)/items").items[0]
    Assert ($revokedItem.state -eq 'ERROR' -and $revokedItem.errorCode -eq 'AUTHORIZATION_REVOKED' -and (Count '/revoked-before-dispatch') -eq 0) 'Frozen approval bypassed a revoked current policy.'
    Pass 'actual-current-target-authorization-revocation'
    $null=Api PATCH "$script:base/target-policies/$approvedTargetPolicy" @{version=1;enabled=$true}
    Stop-Child $worker
    $cancelled=Submit (Case '排队取消' '/never-dispatched')
    $null=Api POST "$script:base/runs/$($cancelled.id)/cancel" @{} 200
    $worker=Start-Worker 'worker-cancellation'
    $null=Wait-Run $cancelled.id 'CANCELLED'
    Assert ((Count '/never-dispatched') -eq 0) 'A cancelled queued task was dispatched.'
    Pass 'queued-cancellation-without-dispatch'

    $mutating=Case '合成副作用与取消' '/mutate' 15000 'POST'
    $before=[int](Stats).mutations
    $inflight=Submit $mutating
    Wait-Mutations ($before+1)
    $null=Api POST "$script:base/runs/$($inflight.id)/cancel" @{} 202
    $run=Wait-Run $inflight.id 'CANCELLED'
    Assert ($run.outcome -eq 'INCOMPLETE' -and $run.metrics.unknown -eq 1) 'In-flight cancellation invented a known result.'
    Assert ([int](Stats).mutations -eq $before+1) 'Cancellation replayed the external operation.'
    Pass 'in-flight-cancellation-preserves-uncertainty'

    $crashing=Submit $mutating
    Wait-Mutations ($before+2)
    Stop-Child $worker
    $interrupted=Wait-Run $crashing.id 'INTERRUPTED'
    Assert ($interrupted.metrics.unknown -eq 1 -and $interrupted.outcome -eq 'INCOMPLETE') 'Worker loss was not classified as uncertain interruption.'
    $worker=Start-Worker 'worker-after-crash'
    Start-Sleep -Seconds 2
    Assert ([int](Stats).mutations -eq $before+2) 'Restart automatically replayed an interrupted operation.'
    Assert ((Api GET "$script:base/runs/$($crashing.id)").state -eq 'INTERRUPTED') 'A terminal interrupted task was reopened.'
    Pass 'forced-worker-loss-lease-recovery-without-automatic-replay'

    # This check temporarily pauses only this Compose project's MySQL. Other development
    # API/Worker instances must be stopped; the fixture and tested children remain isolated.
    $otherApps=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and ($_.CommandLine.Contains($apiJar) -or $_.CommandLine.Contains($workerJar)) -and $_.ProcessId -notin @($children | ForEach-Object Id)
    })
    Assert ($otherApps.Count -eq 0) 'Stop other TestFlow API/Worker instances before the MySQL outage check.'
    $databaseLoss=Submit $mutating
    Wait-Mutations ($before+3)
    Set-TestFlowDockerEnvironment
    $compose=Join-Path $script:TestFlowRoot 'compose.yml'
    $paused=$false
    try {
        & docker compose -f $compose pause mysql
        if ($LASTEXITCODE -ne 0) { throw 'Could not pause the project test database.' }
        $paused=$true
        Start-Sleep -Seconds 8
    } finally {
        if ($paused) {
            & docker compose -f $compose unpause mysql
            if ($LASTEXITCODE -ne 0) { throw 'MySQL must be unpaused before continuing.' }
        }
    }
    $recovered=Wait-Run $databaseLoss.id 'INTERRUPTED' 40
    Assert ($recovered.metrics.unknown -eq 1 -and $recovered.outcome -eq 'INCOMPLETE') 'Database outage did not preserve uncertain execution.'
    Assert ([int](Stats).mutations -eq $before+3) 'Database recovery replayed an external side effect.'
    Pass 'database-outage-recovers-without-late-results-or-http-replay'

    $checks.ToArray() | ForEach-Object { [pscustomobject]@{Check=$_;Passed=$true} } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'checks.json') -Encoding utf8
    @{Passed=$true;Count=$checks.Count;CheckedAt=(Get-Date).ToString('o');Scope='real API JAR + real Worker JAR + MySQL/Redis + synthetic loopback target';TestLeaseMs=3000;TestHeartbeatMs=500} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding utf8
    Write-Host "All $($checks.Count) case execution independent-process checks passed. Test timings are not performance benchmarks."
} finally {
    foreach ($child in $children) { Stop-Child $child }
}
