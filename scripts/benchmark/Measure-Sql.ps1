<#
.SYNOPSIS
在独立benchmark库测量SQL及索引优化前后耗时。
.DESCRIPTION
需要已迁移测试库；先Before建立合成元数据，再After测新索引。
.PARAMETER Phase
选择测试步骤或SQL对照阶段；每次仅传一个受支持值。
.NOTES
会写固定隔离benchmark库和切换其中索引，不执行5万次HTTP；结果：.local/benchmarks。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([ValidateSet('Before','After')][string]$Phase='Before')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
$directory=Join-Path $script:TestFlowRoot '.local/benchmarks'
$null=New-Item -ItemType Directory -Path $directory -Force
function Has-Index([string]$Table,[string]$Name) {
    return [int](@(Invoke-TestFlowSql -Sql "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='testflow_stage6_benchmark' AND table_name='$Table' AND index_name='$Name';")[-1]) -gt 0
}
if ($Phase -eq 'Before') {
    # Isolated, clearly synthetic metadata. These rows did NOT execute HTTP requests.
    Invoke-TestFlowSql -Sql @'
CREATE DATABASE IF NOT EXISTS testflow_stage6_benchmark;
CREATE TABLE IF NOT EXISTS testflow_stage6_benchmark.tf_test_run LIKE testflow_test.tf_test_run;
CREATE TABLE IF NOT EXISTS testflow_stage6_benchmark.tf_outbox_event LIKE testflow_test.tf_outbox_event;
INSERT IGNORE INTO testflow_stage6_benchmark.tf_test_run
(id,project_id,requested_by,idempotency_key,request_hash,kind,source_id,env_id,source_name_snapshot,environment_name_snapshot,requested_by_name,state,outcome,created_at,queued_at,started_at,finished_at,deadline_at,total_count,passed_count)
SELECT n+1,1,1,CONCAT('synthetic-',n),REPEAT('0',64),'CASE',1,1,REPEAT('S',100),'Synthetic benchmark','synthetic','COMPLETED','PASSED',TIMESTAMPADD(SECOND,n,'2026-10-01'),TIMESTAMPADD(SECOND,n,'2026-10-01'),TIMESTAMPADD(SECOND,n,'2026-10-01'),TIMESTAMPADD(SECOND,n+1,'2026-10-01'),TIMESTAMPADD(SECOND,n+600,'2026-10-01'),1,1
FROM (SELECT a.n+b.n*10+c.n*100+d.n*1000+e.n*10000 n FROM JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]','$[*]' COLUMNS(n INT PATH '$')) a CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]','$[*]' COLUMNS(n INT PATH '$')) b CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]','$[*]' COLUMNS(n INT PATH '$')) c CROSS JOIN JSON_TABLE('[0,1,2,3,4,5,6,7,8,9]','$[*]' COLUMNS(n INT PATH '$')) d CROSS JOIN JSON_TABLE('[0,1,2,3,4]','$[*]' COLUMNS(n INT PATH '$')) e) seq;
INSERT IGNORE INTO testflow_stage6_benchmark.tf_outbox_event(event_id,run_id,project_id,payload,status,next_attempt_at,published_at)
SELECT LPAD(id,36,'0'),id,project_id,JSON_OBJECT('synthetic',true),IF(id>49990,'PENDING','PUBLISHED'),UTC_TIMESTAMP(3),IF(id>49990,NULL,UTC_TIMESTAMP(3)) FROM testflow_stage6_benchmark.tf_test_run;
ANALYZE TABLE testflow_stage6_benchmark.tf_test_run,testflow_stage6_benchmark.tf_outbox_event;
'@ | Out-Null
}
# Normalize ONLY this dedicated benchmark schema, so rerunning after V6 remains reproducible.
if($Phase -eq 'Before') {
    if(Has-Index 'tf_test_run' 'idx_run_finished'){Invoke-TestFlowSql -Sql 'ALTER TABLE testflow_stage6_benchmark.tf_test_run DROP INDEX idx_run_finished;' | Out-Null}
    foreach($index in @('ix_outbox_pending','ix_outbox_expired')) {
        if(Has-Index 'tf_outbox_event' $index){Invoke-TestFlowSql -Sql "ALTER TABLE testflow_stage6_benchmark.tf_outbox_event DROP INDEX $index;" | Out-Null}
    }
    if(-not (Has-Index 'tf_outbox_event' 'ix_outbox_dispatch')){Invoke-TestFlowSql -Sql 'ALTER TABLE testflow_stage6_benchmark.tf_outbox_event ADD INDEX ix_outbox_dispatch(status,next_attempt_at,lease_until);' | Out-Null}
} else {
    if(-not (Has-Index 'tf_test_run' 'idx_run_finished')){Invoke-TestFlowSql -Sql 'ALTER TABLE testflow_stage6_benchmark.tf_test_run ADD INDEX idx_run_finished(project_id,finished_at,id);' | Out-Null}
    if(Has-Index 'tf_outbox_event' 'ix_outbox_dispatch'){Invoke-TestFlowSql -Sql 'ALTER TABLE testflow_stage6_benchmark.tf_outbox_event DROP INDEX ix_outbox_dispatch;' | Out-Null}
    if(-not (Has-Index 'tf_outbox_event' 'ix_outbox_pending')){Invoke-TestFlowSql -Sql 'ALTER TABLE testflow_stage6_benchmark.tf_outbox_event ADD INDEX ix_outbox_pending(status,next_attempt_at,event_id), ADD INDEX ix_outbox_expired(status,lease_until,event_id);' | Out-Null}
}
$history=if($Phase -eq 'Before') {'SELECT r.* FROM tf_test_run r WHERE project_id=1 ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET 40000'}else{'SELECT r.* FROM tf_test_run r JOIN (SELECT id FROM tf_test_run WHERE project_id=1 ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET 40000) page ON page.id=r.id ORDER BY r.created_at DESC,r.id DESC'}
$dispatch=if($Phase -eq 'Before') {"SELECT * FROM tf_outbox_event WHERE (status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3)) OR (status='PUBLISHING' AND lease_until<=UTC_TIMESTAMP(3)) ORDER BY next_attempt_at,event_id LIMIT 1"}else{"SELECT * FROM tf_outbox_event WHERE status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY next_attempt_at,event_id LIMIT 1"}
$queries=@{deepHistory=$history;outboxClaim=$dispatch;finishedWindow="SELECT COUNT(*) FROM tf_test_run WHERE project_id=1 AND finished_at>='2026-10-01 12:00:00' AND finished_at<'2026-10-01 12:10:00'"}
$actualIds=(@(Invoke-TestFlowSql -Sql "USE testflow_stage6_benchmark; SELECT id FROM tf_test_run WHERE project_id=1 ORDER BY created_at DESC,id DESC LIMIT 20 OFFSET 40000;") -join ',')
$expectedIds=(@(10000..9981) -join ',')
if($actualIds -ne $expectedIds){throw 'Unexpected benchmark ordering/data; refusing misleading measurement.'}
$results=@{}
foreach($entry in $queries.GetEnumerator()) {
    $plans=@(Invoke-TestFlowSql -Sql "USE testflow_stage6_benchmark; EXPLAIN ANALYZE $($entry.Value);")
    [IO.File]::WriteAllLines((Join-Path $directory "sql-$($Phase.ToLowerInvariant())-$($entry.Key).txt"),[string[]]$plans)
    $times=@()
    # MySQL EXPLAIN ANALYZE reports actual execution times, excluding Docker CLI startup overhead.
    for($i=0;$i -lt 21;$i++) {
        $plan=(@(Invoke-TestFlowSql -Sql "USE testflow_stage6_benchmark; EXPLAIN ANALYZE $($entry.Value);") -join "`n")
        $match=[regex]::Match($plan,'actual time=[0-9.]+\.\.([0-9.]+)')
        if(-not $match.Success){throw 'Cannot parse actual MySQL execution time.'}
        if($i -gt 0){$times += [double]::Parse($match.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture)}
    }
    $sorted=@($times | Sort-Object)
    $results[$entry.Key]=@{query=$entry.Value;measurementsMs=$times;count=$times.Count;averageMs=($times|Measure-Object -Average).Average;p95Ms=$sorted[[Math]::Ceiling(.95*$sorted.Count)-1];p99Ms=$sorted[[Math]::Ceiling(.99*$sorted.Count)-1]}
}
$output=@{phase=$Phase;at=[DateTime]::UtcNow.ToString('o');rows=50000;data='Synthetic metadata only; no HTTP execution implied';warmups=1;results=$results}
[IO.File]::WriteAllText((Join-Path $directory "sql-$($Phase.ToLowerInvariant()).json"),($output|ConvertTo-Json -Depth 10))
Write-Host "SQL $Phase measured on 50000 isolated synthetic metadata rows."
