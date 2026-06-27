# 确保 Outbox 相关 Kafka 主题存在（本地 Docker Kafka 容器名为 kafka）
# 用法：.\scripts\dev\ensure-kafka-topics.ps1

$ErrorActionPreference = "Stop"
$container = "kafka"

function Ensure-Topic {
    param(
        [string]$Name,
        [int]$Partitions = 1
    )
    docker exec $container /opt/kafka/bin/kafka-topics.sh `
        --bootstrap-server localhost:9092 `
        --create --if-not-exists `
        --topic $Name `
        --partitions $Partitions `
        --replication-factor 1
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to ensure topic: $Name"
    }
    Write-Host "OK: $Name"
}

Write-Host "Ensuring Kafka topics in container '$container'..."
Ensure-Topic -Name "canal-outbox" -Partitions 3
Ensure-Topic -Name "canal-outbox-dlq" -Partitions 1
Write-Host "Done."
