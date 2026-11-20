<#
.SYNOPSIS
对比积压Outbox在不同索引下的锁定领取查询。
.DESCRIPTION
先运行SQL测量准备隔离benchmark库和合成表。
.NOTES
会修改合成表与索引，不实际发布事件；结果：.local/benchmarks。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
$directory=Join-Path $script:TestFlowRoot '.local/benchmarks'
$null=New-Item -ItemType Directory -Path $directory -Force
# This schema/table is dedicated synthetic benchmark material, never an application table.
Invoke-TestFlowSql -Sql @'
CREATE TABLE IF NOT EXISTS testflow_stage6_benchmark.tf_outbox_v5 LIKE testflow_stage6_benchmark.tf_outbox_event;
INSERT IGNORE INTO testflow_stage6_benchmark.tf_outbox_v5 SELECT * FROM testflow_stage6_benchmark.tf_outbox_event;
UPDATE testflow_stage6_benchmark.tf_outbox_v5 SET status=IF(run_id>40000,'PENDING','PUBLISHED'),next_attempt_at=UTC_TIMESTAMP(3);
UPDATE testflow_stage6_benchmark.tf_outbox_event SET status=IF(run_id>40000,'PENDING','PUBLISHED'),next_attempt_at=UTC_TIMESTAMP(3);
'@ | Out-Null
$indexes=@(Invoke-TestFlowSql -Sql "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema='testflow_stage6_benchmark' AND table_name='tf_outbox_v5';")
foreach($index in @('ix_outbox_pending','ix_outbox_expired')){if($indexes -contains $index){Invoke-TestFlowSql -Sql "ALTER TABLE testflow_stage6_benchmark.tf_outbox_v5 DROP INDEX $index;" | Out-Null}}
if($indexes -notcontains 'ix_outbox_dispatch'){Invoke-TestFlowSql -Sql 'ALTER TABLE testflow_stage6_benchmark.tf_outbox_v5 ADD INDEX ix_outbox_dispatch(status,next_attempt_at,lease_until);' | Out-Null}
$result=@{at=[DateTime]::UtcNow.ToString('o');rows=50000;pending=10000;data='Synthetic metadata only; isolated tables, no Kafka throughput implied';samples=20;results=@{}}
$queries=@{
    v5="SELECT * FROM tf_outbox_v5 WHERE (status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3)) OR (status='PUBLISHING' AND lease_until<=UTC_TIMESTAMP(3)) ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED"
    v6="SELECT * FROM tf_outbox_event WHERE status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3) ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED"
}
foreach($entry in $queries.GetEnumerator()) {
    $times=@();$plan=''
    for($n=0;$n -lt 21;$n++) {
        $plan=(@(Invoke-TestFlowSql -Sql "USE testflow_stage6_benchmark; START TRANSACTION; EXPLAIN ANALYZE $($entry.Value); ROLLBACK;") -join "`n")
        $match=[regex]::Match($plan,'actual time=[0-9.]+\.\.([0-9.]+)')
        if(-not $match.Success){throw 'Cannot parse actual plan timing.'}
        if($n -gt 0){$times += [double]::Parse($match.Groups[1].Value,[Globalization.CultureInfo]::InvariantCulture)}
    }
    [IO.File]::WriteAllText((Join-Path $directory "outbox-dense-$($entry.Key).txt"),$plan)
    $sorted=@($times|Sort-Object)
    $result.results[$entry.Key]=@{query=$entry.Value;measurementsMs=$times;averageMs=($times|Measure-Object -Average).Average;p95Ms=$sorted[18];p99Ms=$sorted[19]}
}
[IO.File]::WriteAllText((Join-Path $directory 'outbox-dense.json'),($result|ConvertTo-Json -Depth 8))
Write-Host 'Compared V5/V6 locking queries with 10000 synthetic pending events; no events actually published.'
