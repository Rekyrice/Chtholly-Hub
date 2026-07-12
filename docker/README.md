# Docker 操作入口

生产拓扑、发布验证和回滚边界见[生产部署](../docs/operations/deployment.md)。本页只记录仓库当前可执行的容器命令与维护者本机约定。

## 本地外部基础设施

仓库没有通用的开发环境 Compose。当前维护者机器约定外部容器名为 `mysql`、`redis`、`kafka`、`elasticsearch`，常用端口分别为 3306、6379、9092、9200；持久化目录由机器自己的 `D:\1.hhh\Application\Docker` 管理。其他开发者应提供等价服务并在根 `.env` 配置地址，不要依赖该绝对路径。

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
docker exec -e MYSQL_PWD='你的密码' mysql mysql -uroot -e "USE chtholly; SHOW TABLES;"
Invoke-RestMethod http://localhost:9200
```

`.env.example` 显式设置 `KAFKA_ENABLED=true`。没有 Kafka 时改为 `false` 使用进程内 fallback；使用约定容器时可补齐 Outbox 主题：

```powershell
.\scripts\dev\ensure-kafka-topics.ps1
```

## 生产 Compose

```bash
cp .env.prod.example .env
# 编辑 .env，替换密码、域名和实际启用功能的凭据
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=100 server web nginx
curl -fsS http://127.0.0.1/health
```

当前 Compose 始终启动 Kafka，并让 server 等待其健康；`KAFKA_ENABLED=false` 只关闭应用使用 Kafka，不会从 Compose 中移除服务。server 同样在 Compose 中固定 `LLM_ENABLED=false` 和 `CANAL_ENABLED=false`。存储默认 `local`，通过 `uploads_data` 供 server 写入、Nginx 读取。

首次部署可运行 `bash scripts/deploy/ecs-bootstrap.sh`；它包含数据库初始化。数据库真实流程见[数据库章节](../docs/development/database.md)，不要把 SQL 文件名约定理解为已启用 Flyway。

## Nginx 与 Caddy

- 默认 [`nginx/default.conf`](nginx/default.conf)：HTTP 入口；`/api/`、Agent WebSocket、`/health` 转发后端，`/uploads/` 读取共享卷，其余转发 Next.js。
- [`caddy/Caddyfile`](caddy/Caddyfile) 与根 [`docker-compose.caddy.example.yml`](../docker-compose.caddy.example.yml)：自动 HTTPS 示例。使用前替换域名和联系邮箱，不要与 Nginx 方案同时占用端口。

## 常见故障

| 现象 | 检查 |
|------|------|
| 首页 502 | `docker compose ... ps` 与 `logs server web nginx`；确认 MySQL、Redis、ES、Kafka 健康 |
| `/health` 失败 | server 日志、数据库凭据、依赖服务地址与健康检查 |
| 搜索无结果 | Elasticsearch `_cluster/health`，再重启 server 触发索引回灌 |
| 正文或上传 404 | `STORAGE_TYPE`、`uploads_data` 挂载，或 OSS bucket/object key |
| Kafka 链路异常 | `KAFKA_ENABLED`、bootstrap 地址、主题与 broker 健康 |

生产回滚、备份和不可逆数据库边界统一见[生产部署](../docs/operations/deployment.md)。不要对含数据的环境执行未经确认的 `docker compose down -v`。
