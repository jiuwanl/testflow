<#
.SYNOPSIS
准备开发与测试的Kafka及各自消息主题。
.DESCRIPTION
需要已初始化开发配置和可用Docker引擎；单节点质量统计依赖。
.NOTES
会启动Kafka并初始化数据卷和主题，不负责API或Worker启动。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
Set-TestFlowDockerEnvironment
Push-Location $script:TestFlowRoot
try {
    # The official image runs as uid 1000; a fresh Docker volume is root-owned.
    & docker compose --profile quality run --rm --no-deps --user root --entrypoint sh kafka -c 'chown 1000:1000 /tmp/kraft-combined-logs'
    if($LASTEXITCODE -ne 0){throw 'Kafka data volume initialization failed'}
    & docker compose --profile quality up -d --wait kafka
    if($LASTEXITCODE -ne 0){throw 'Kafka did not become healthy'}
    foreach($topic in @('testflow.run-finalized.v1','testflow-test.run-finalized.v1')){
        & docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic $topic --partitions 1 --replication-factor 1 --config retention.ms=604800000 --config retention.bytes=268435456 --config max.message.bytes=32768
        if($LASTEXITCODE -ne 0){throw 'Quality topic initialization failed'}
    }
    Write-Host 'Single-node local Kafka and separate development/test topics ready.'
}finally{Pop-Location}
