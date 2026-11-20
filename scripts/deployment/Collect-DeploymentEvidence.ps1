<#
.SYNOPSIS
采集运行中完整部署的状态及数据库观察值。
.DESCRIPTION
需要完整部署就绪；只读容器、资源、SQL容量和一致性观察。
.NOTES
结果：.local/verification/deployment/deployment.json；不作为日常入口，不修改业务记录。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
. (Join-Path $PSScriptRoot '../lib/ProjectPaths.ps1')
$ErrorActionPreference='Stop'
$root=$script:TestFlowRoot
$directory=Join-Path $root '.local/verification/deployment'
$null=New-Item -ItemType Directory -Path $directory -Force
Push-Location $root
try {
    $engine=& docker info --format '{{json .}}' | ConvertFrom-Json
    if($LASTEXITCODE -ne 0){throw 'Docker engine unavailable.'}
    $containers=@()
    foreach($id in @(& docker compose -f compose.deploy.yml ps -q)) {
        $item=(& docker inspect $id | ConvertFrom-Json)[0]
        $health='not-configured'
        $healthProperty=$item.State.PSObject.Properties['Health']
        if($null -ne $healthProperty){$health=$healthProperty.Value.Status}
        $containers+=@{name=$item.Name.TrimStart('/');imageId=$item.Image;memoryLimitBytes=$item.HostConfig.Memory;user=$item.Config.User;health=$health;restarts=$item.RestartCount;oomKilled=$item.State.OOMKilled;networks=@($item.NetworkSettings.Networks.PSObject.Properties.Name);ports=@($item.NetworkSettings.Ports.PSObject.Properties|Where-Object Value|ForEach-Object{@{containerPort=$_.Name;bindings=$_.Value}})}
    }
    $queries=@{
        connections="SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Max_used_connections','Slow_queries','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests');"
        storage="SELECT table_name,table_rows,data_length,index_length FROM information_schema.tables WHERE table_schema='testflow' ORDER BY table_name;"
        payloads="SELECT 'outbox',COUNT(*),ROUND(AVG(OCTET_LENGTH(CAST(payload AS CHAR))),2),MAX(OCTET_LENGTH(CAST(payload AS CHAR))) FROM tf_outbox_event UNION ALL SELECT 'snapshots',COUNT(*),ROUND(AVG(OCTET_LENGTH(encrypted_payload)),2),MAX(OCTET_LENGTH(encrypted_payload)) FROM tf_run_snapshot UNION ALL SELECT 'results',COUNT(*),ROUND(AVG(OCTET_LENGTH(CAST(report_json AS CHAR))),2),MAX(OCTET_LENGTH(CAST(report_json AS CHAR))) FROM tf_run_item_result;"
        consistency="SELECT 'terminal_without_outbox',COUNT(*) FROM tf_test_run r LEFT JOIN tf_outbox_event e ON e.run_id=r.id WHERE r.state IN ('COMPLETED','CANCELLED','TIMED_OUT','INTERRUPTED') AND e.event_id IS NULL UNION ALL SELECT 'terminal_without_fact',COUNT(*) FROM tf_test_run r LEFT JOIN tf_quality_run_fact q ON q.run_id=r.id WHERE r.state IN ('COMPLETED','CANCELLED','TIMED_OUT','INTERRUPTED') AND q.run_id IS NULL UNION ALL SELECT 'active',COUNT(*) FROM tf_test_run WHERE state IN ('QUEUED','RUNNING','CANCELLING');"
    }
    $database=@{}
    $performancePath=Join-Path $root '.local/benchmarks/performance.json'
    if(Test-Path -LiteralPath $performancePath) {
        $project=(Get-Content -LiteralPath $performancePath -Raw | ConvertFrom-Json).fixture.project
        if("$project" -notmatch '^[1-9][0-9]*$'){throw 'Invalid performance fixture ID.'}
        $queries.performanceCohort="SELECT id,state,outcome,total_count,passed_count,TIMESTAMPDIFF(MICROSECOND,started_at,finished_at) DIV 1000 FROM tf_test_run WHERE project_id=$project ORDER BY id;"
    }
    foreach($query in $queries.GetEnumerator()) {
        $lines=@($query.Value | & docker compose -f compose.deploy.yml exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_root_password)" exec mysql -uroot --batch --skip-column-names testflow')
        if($LASTEXITCODE -ne 0){throw 'Deployment SQL observation failed.'}
        $database[$query.Key]=$lines
    }
    $result=@{at=[DateTime]::UtcNow.ToString('o');engine=@{version=$engine.ServerVersion;os=$engine.OSType;architecture=$engine.Architecture;cpus=$engine.NCPU;memoryBytes=$engine.MemTotal};containers=$containers;database=$database}
    [IO.File]::WriteAllText((Join-Path $directory 'deployment.json'),($result|ConvertTo-Json -Depth 12))
    Write-Host 'Collected sanitized deployment identity, resources, SQL capacity and consistency observations.'
} finally {Pop-Location}
