# Docker 操作入口

生产拓扑、发布验证和回滚边界见[生产部署](../docs/operations/deployment.md)。本页只记录仓库当前可执行的容器命令与维护者本机约定。

## 本地外部基础设施

仓库没有通用的开发环境 Compose。当前维护者机器约定外部容器名为 `mysql`、`redis`、`kafka`、`elasticsearch`，常用端口分别为 3306、6379、9092、9200；持久化目录由机器自己的 `D:\1.hhh\Application\Docker` 管理。其他开发者应提供等价服务并在根 `.env` 配置地址，不要依赖该绝对路径。

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
docker exec -e MYSQL_PWD='你的密码' mysql mysql -uroot -e "USE chtholly; SHOW TABLES;"
Invoke-RestMethod http://localhost:9200
```

`.env.example` 显式设置 `KAFKA_ENABLED=true`、`CANAL_ENABLED=false`。该组合让浏览量等通用计数使用 Kafka，让点赞/收藏 Outbox 使用进程内 `AFTER_COMMIT` 路径；两个开关都为 `true` 时，代码选择互动 Kafka 传输。启用前必须确认 CDC 可达，运行时不会因 CDC 断开自动切回本地适配器。使用约定容器时可创建 `canal-outbox`（3 分区）、`canal-outbox-retry`（3 分区）与 `canal-outbox-dlq`（1 分区）：

```powershell
.\scripts\dev\ensure-kafka-topics.ps1
```

## 受支持的一键生产 Compose

```bash
# 推荐先在维护机运行被 Git 忽略的 .local-deploy/prepare-production.ps1，上传 bundle 并执行 install.sh
bash scripts/deploy/ecs-bootstrap.sh
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=100 server web nginx
curl -fsS http://127.0.0.1/health
```

当前 Compose 默认不创建 Kafka，server 使用 `KAFKA_ENABLED=false`、`CANAL_ENABLED=false`；需要 broker 时同时设置 `COMPOSE_PROFILES=kafka`、`KAFKA_ENABLED=true`。LLM 默认通过 `SPRING_PROFILES_ACTIVE=llm` 与 `LLM_ENABLED=true` 启用，两个 API key 和外部 JWT 密钥必须由配置包或人工提供。存储默认 `local`，通过 `uploads_data` 供 server 写入、Nginx 读取。

这是当前受支持的 Nginx 一键生产路径。首次部署可运行 `bash scripts/deploy/ecs-bootstrap.sh`；它只初始化空数据库，生产默认不导入 seed。HTTP 可用且 DNS 生效后运行 `bash scripts/deploy/ecs-enable-https.sh <domain> <email>`。三个 ECS 脚本都硬编码 `docker-compose.prod.yml`，只能用于本节路径。数据库真实流程见[数据库章节](../docs/development/database.md)，不要把 SQL 文件名约定理解为已启用 Flyway。

## Nginx 与 Caddy 参考模板

- 默认 [`nginx/default.conf`](nginx/default.conf)：HTTP 与 ACME 入口；`/api/`、Agent WebSocket、`/health` 转发后端，`/uploads/` 读取共享卷，其余转发 Next.js。HTTPS 由 [`nginx/https.conf.template`](nginx/https.conf.template) 安全切换。
- [`caddy/Caddyfile`](caddy/Caddyfile) 与根 [`docker-compose.caddy.example.yml`](../docker-compose.caddy.example.yml) 只是自动 HTTPS 参考模板，不是当前受支持的一键路径。不要与 Nginx 栈同时启动，也不要在 Caddy 路径运行 `ecs-bootstrap.sh` 或 `ecs-init-db.sh`；脚本会操作硬编码的 prod Compose/Nginx 栈并可能造成端口冲突。
- Caddy 示例的 web build 缺少 prod Compose 已传入的 `NEXT_PUBLIC_SITE_URL` 与 `NEXT_PUBLIC_OSS_PUBLIC_URL`。直接构建可能让 canonical/Open Graph 回退 localhost，并缺少自定义 OSS 的 Next Image 来源。投入生产前需自行对齐 build args，并补齐数据库初始化、健康验证和回滚流程。

## 常见故障

| 现象 | 检查 |
|------|------|
| 首页 502 | `docker compose ... ps` 与 `logs server web nginx`；确认 MySQL、Redis、ES 健康；启用 profile 后再检查 Kafka |
| `/health` 失败 | server 日志、数据库凭据、依赖服务地址与健康检查 |
| 搜索无结果 | Elasticsearch `_cluster/health`，再重启 server 触发索引回灌 |
| 正文或上传 404 | `STORAGE_TYPE`、`uploads_data` 挂载，或 OSS bucket/object key |
| Kafka 链路异常 | `KAFKA_ENABLED`/`CANAL_ENABLED` 配对、bootstrap/CDC 地址、三个 Outbox 主题、consumer lag、`dead_letter_messages` 的 `RETRYING`/`DEAD` 状态与 broker 健康；broker 健康不代表 CDC 已接通 |

生产回滚、备份和不可逆数据库边界统一见[生产部署](../docs/operations/deployment.md)。不要对含数据的环境执行未经确认的 `docker compose down -v`。
