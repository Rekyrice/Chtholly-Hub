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

Outbox 主题：`canal-outbox`（增量索引）、`canal-outbox-dlq`（消费失败死信）。  
**后端启动时** Spring `KafkaAdmin` 会自动创建（见 `OutboxKafkaTopicConfig`）；也可手动执行：

```powershell
.\scripts\dev\ensure-kafka-topics.ps1
```
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

HTTPS：在 Nginx 前加云厂商 LB / Certbot，或取消注释 `docker/nginx/default.conf` 末尾的 443 模板并挂载证书。

**不想手动配证书？** 可使用 Caddy 自动 HTTPS 示例：

```bash
# 编辑 docker/caddy/Caddyfile 中的域名后
docker compose -f docker-compose.caddy.example.yml up -d --build
```

Caddy 会从 Let's Encrypt 自动申请证书，并启用 HSTS。

## ECS 单机部署（M1-7）

### 这一步在做什么？为什么要做？

本地开发时，你在自己电脑上分别跑：

- 外部 Docker 里的 MySQL / Redis / Kafka / ES  
- `scripts/dev` 启动的后端 `:8888`  
- `npm run dev` 的前端 `:3000`  

**只有本机能访问**，别人打不开你的博客，你也无法验证「上线」后的真实行为（Nginx 反代、容器网络、生产构建等）。

**ECS + Compose 要做的事：** 把**同一套依赖 + 前后端**打包进 Docker，在**一台云服务器**上用一条命令拉起来，并通过 **80 端口**对外提供服务：

```text
用户浏览器
    → ECS 公网 IP:80 (Nginx)
        → /api/*  → Spring Boot 容器
        → 其余    → Next.js standalone 容器
    → 内网      → MySQL / Redis / ES / Kafka（不暴露公网）
```

**为什么要 Compose，而不是手动装 MySQL、再 java -jar？**

| 目的 | 说明 |
|------|------|
| **可重复** | 换机器 / 重装时 `git clone` + 脚本即可，不依赖「我记得上次怎么配的」 |
| **环境一致** | 与本地相同的 Java 21、Node 20、ES 8.11，减少「我电脑能跑」问题 |
| **M1 里程碑** | 计划里的「可上线」= 公网能访问 Feed / 搜索 / 登录，Compose 是最小可用部署形态 |
| **同域 API** | Nginx 把 `/api` 和页面放在同一域名，浏览器无需额外 CORS 配置 |

OSS 正文仍走阿里云对象存储，**不放进 Compose**（静态 Markdown 已在 bucket 里）。

### 前置条件（阿里云 ECS）

1. 规格建议 **4C8G**，系统 **Ubuntu 22.04 / Alibaba Cloud Linux 3**
2. 安全组放行 **入站 TCP 80**（HTTPS 再加 443）
3. 已安装 **Docker Engine + Compose 插件**（`docker compose version` 可用）
4. 仓库已 clone 到服务器，例如 `/opt/Chtholly-Hub`

### 一键脚本（在 ECS 上执行）

```bash
cd /opt/Chtholly-Hub
cp .env.prod.example .env
nano .env   # 必填 MYSQL_PASSWORD、OSS_* 等

chmod +x scripts/deploy/ecs-bootstrap.sh scripts/deploy/ecs-init-db.sh
bash scripts/deploy/ecs-bootstrap.sh
```

脚本会：`build` → `up -d` → 导入 schema/migration/seed → 重启 server（ES 回灌）→ curl 自检首页与 Feed API。

**OSS 种子正文**需在能访问 OSS 的机器上执行（本机或 ECS 装 Node 后）：

```bash
# .env 中已有 OSS 凭证时
node scripts/oss/upload-seed-markdown.mjs
```

### 手动分步（与脚本等价）

```bash
docker compose -f docker-compose.prod.yml up -d --build
bash scripts/deploy/ecs-init-db.sh
docker compose -f docker-compose.prod.yml restart server
curl http://127.0.0.1/api/v1/posts/feed?page=1&size=1
```

### 常见问题

| 现象 | 处理 |
|------|------|
| 构建拉镜像超时 | 配置 Docker 镜像加速；或在本机构建后 `docker save/load` 到 ECS |
| 首页 502 | `docker compose logs server web nginx`；多为 DB 未初始化或 server 未连上 mysql |
| 搜索无结果 | 确认 ES 容器 healthy，`restart server` 触发索引回灌 |
| 详情页正文 404 | OSS 未上传或 `content_url` 与 bucket 路径不一致 |

## 与本项目的关系

1. 复制 Monorepo 根目录 `.env.example` → `.env`，填入 MySQL 密码与 OSS 凭证  
2. 初始化数据库与种子：见 [apps/server/db/README.md](../apps/server/db/README.md)  
3. 启动 `apps/server`（`:8888`）与 `apps/web`（`:3000`）

详见仓库根目录 [README.md](../README.md)。
