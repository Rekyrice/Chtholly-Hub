# 本地基础设施说明

Chtholly Hub **不在本仓库内启动** MySQL / Redis / Kafka / Elasticsearch。  
本地依赖由外部目录统一管理：

**`D:\1.hhh\Application\Docker`**

## 运行中的服务

| 服务 | 端口 | 数据目录（D 盘） |
|------|------|------------------|
| MySQL 8 | 3306 | `mysql_data` |
| Redis | 6379 | `redisSave` |
| Kafka | 9092 | `kafka_data` |
| Elasticsearch | 9200 | `elasticsearch_data` |

数据库名：**`chtholly`**。

## 启动 / 检查

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

MySQL 连接示例（PowerShell 下密码含 `!` 时用环境变量，避免交互 `-p` 出错）：

```powershell
docker exec -e MYSQL_PWD='你的密码' mysql mysql -uroot -e "USE chtholly; SHOW TABLES;"
```

## Elasticsearch 单独启动（若未运行）

**只映射 9200**，不要映射 9300（Windows 可能占用 9223–9322 端口段）：

```powershell
docker run -d --name elasticsearch --restart always `
  -p 9200:9200 `
  -v "D:/1.hhh/Application/Docker/elasticsearch_data:/usr/share/elasticsearch/data" `
  -e "discovery.type=single-node" `
  -e "xpack.security.enabled=false" `
  -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" `
  -e "cluster.routing.allocation.disk.watermark.low=95%" `
  -e "cluster.routing.allocation.disk.watermark.high=97%" `
  -e "cluster.routing.allocation.disk.watermark.flood_stage=99%" `
  docker.elastic.co/elasticsearch/elasticsearch:8.11.0
```

单节点 + D 盘空间紧张时，默认 90% 高水位会导致索引分片无法分配（`red`）。上面三行把阈值放宽到 **97% / 99%**（仅开发环境建议）。

已运行的容器可临时调整（重启 ES 后需重跑）：

```powershell
Invoke-RestMethod -Uri "http://localhost:9200/_cluster/settings" -Method Put -ContentType "application/json" -Body '{"persistent":{"cluster.routing.allocation.disk.watermark.low":"95%","cluster.routing.allocation.disk.watermark.high":"97%","cluster.routing.allocation.disk.watermark.flood_stage":"99%"}}'
Invoke-RestMethod -Uri "http://localhost:9200/chtholly_content_index" -Method Delete -ErrorAction SilentlyContinue
Invoke-RestMethod -Uri "http://localhost:9200/_cluster/reroute?retry_failed=true" -Method Post
```

验证：`curl http://localhost:9200`

## 生产部署（M1-3）

单机 **4C8G** ECS 可用仓库内 Compose 一键起站：

```bash
cp .env.prod.example .env
# 编辑 .env：MYSQL_PASSWORD、OSS 凭证等

docker compose -f docker-compose.prod.yml up -d --build
```

| 服务 | 容器内 | 说明 |
|------|--------|------|
| **nginx** | `:80` | 对外入口；`/api` → server，其余 → web |
| **web** | `:3000` | Next.js standalone |
| **server** | `:8888` | Spring Boot |
| mysql / redis / es / kafka | 内网 | 与本地开发同栈 |

**首次部署还需初始化数据库**（在 MySQL 容器就绪后）：

1. 导入 `apps/server/db/schema.sql` 与 migration / seed（见 [apps/server/db/README.md](../apps/server/db/README.md)）
2. 上传 OSS 种子正文：`node scripts/oss/upload-seed-markdown.mjs`
3. 重启 `server` 容器以触发 ES 索引回灌

Nginx 配置：`docker/nginx/default.conf`。内存紧张时可去掉 `kafka` 服务并改用 Spring Event 降级（需后续代码开关；当前默认带 Kafka）。

HTTPS：在 Nginx 前加云厂商 LB / Certbot，或把 `443` 配进 Nginx。

## 与本项目的关系

1. 复制 Monorepo 根目录 `.env.example` → `.env`，填入 MySQL 密码与 OSS 凭证  
2. 初始化数据库与种子：见 [apps/server/db/README.md](../apps/server/db/README.md)  
3. 启动 `apps/server`（`:8888`）与 `apps/web`（`:3000`）

详见仓库根目录 [README.md](../README.md)。
