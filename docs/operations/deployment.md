# 生产部署

## 本地与生产边界

仓库没有通用的本地开发基础设施 Compose。维护者机器现有 MySQL、Redis、Kafka、Elasticsearch 容器的名称、端口与排障命令记录在 [Docker 操作入口](../../docker/README.md)，它们是本机约定，不是可复制的开发环境定义。

当前受支持的一键生产入口是 [`docker-compose.prod.yml`](../../docker-compose.prod.yml) + Nginx：MySQL、Redis、Elasticsearch、Kafka、Spring Boot、Next.js standalone 与 Nginx 运行在同一 Compose 网络，只有 Nginx 暴露 `${HTTP_PORT:-80}`。默认正文存储为具名卷 `uploads_data`；需要 OSS 时必须同时调整存储配置和凭据。

## 部署前准备

1. 安装 Docker Engine 与 Compose 插件，准备可持久化的宿主机磁盘和备份策略。
2. 将 [`.env.prod.example`](../../.env.prod.example) 复制为不会提交的 `.env`，替换数据库密码、站点域名与实际启用功能的凭据。
3. 确认 80/443、安全组、DNS 与 TLS 方案；确认 MySQL 数据、Redis bitmap 状态、上传卷/OSS 的备份边界。
4. 在隔离环境验证镜像构建、数据库脚本和健康检查，再操作已有生产数据。

不要把 `.env`、证书私钥、OSS/LLM/Bangumi 密钥提交到仓库或粘贴进日志与文档。

## 受支持的一键路径：生产 Compose + Nginx

从仓库根目录运行：

```bash
cp .env.prod.example .env
# 编辑 .env
docker compose -f docker-compose.prod.yml up -d --build
```

[`docker/nginx/default.conf`](../../docker/nginx/default.conf) 将 `/api/`、Agent WebSocket 和 `/health` 转给 Spring Boot，将 `/uploads/` 读自共享卷，其余请求转给 Next.js。文件末尾提供手工证书模板，但 `docker-compose.prod.yml` 默认只挂载 HTTP 配置。

[`ecs-bootstrap.sh`](../../scripts/deploy/ecs-bootstrap.sh) 与 [`ecs-init-db.sh`](../../scripts/deploy/ecs-init-db.sh) 只适用于这条 Nginx 路径：两个脚本都把 Compose 文件硬编码为 `docker-compose.prod.yml`。首次部署可运行：

```bash
bash scripts/deploy/ecs-bootstrap.sh
```

脚本构建并启动服务，调用 `ecs-init-db.sh`，重启后端以触发索引回灌，再检查首页与 Feed。数据库脚本的真实顺序是 `schema.sql` → 当前 `migration/V*.sql` → `phase_a_seed.sql`，不是应用内 Flyway 自动迁移。详细关系与已有库边界见[数据库](../development/database.md)。OSS seed 正文需另行执行 `node scripts/oss/upload-seed-markdown.mjs`。

## Caddy 参考模板的限制

[`docker-compose.caddy.example.yml`](../../docker-compose.caddy.example.yml) 与 [`docker/caddy/Caddyfile`](../../docker/caddy/Caddyfile) 只是自动 HTTPS 的参考模板，当前不是受支持的一键生产路径。投入生产前至少需要自行完成以下适配：

- Caddy 示例的 web build 只传递 `API_SERVER_URL`，没有像受支持的 prod Compose 一样传递 `NEXT_PUBLIC_SITE_URL` 与 `NEXT_PUBLIC_OSS_PUBLIC_URL`。未经对齐时，canonical/Open Graph 站点 URL 可能回退到 `http://localhost:3000`，自定义 OSS 来源也不会进入 Next Image 的构建期 allowlist。
- 自行设计并验证 Caddy 路径的数据库初始化、服务重启、健康检查与回滚流程；现有部署脚本不接受 Compose 文件参数。
- 不得在 Caddy 路径运行 `ecs-bootstrap.sh` 或 `ecs-init-db.sh`：它们会启动或操作 `docker-compose.prod.yml` 的 Nginx 栈，可能与已运行的 Caddy 栈争用 80/443 端口并操作错误的服务集合。
- Nginx 与 Caddy 两套栈不得同时启动。完成 build args、初始化与验证流程的二次适配前，不应把 Caddy 模板用于生产。

本节不提供 Caddy 初始化或验证命令，因为仓库尚未提供与该模板匹配的完整流程。

## 验证与排障

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=100 server web nginx
curl -fsS http://127.0.0.1/health
curl -fsS 'http://127.0.0.1/api/v1/posts/feed?page=1&size=1'
```

还应验证登录、详情正文/上传文件、搜索与 Agent 等实际启用功能。首页 502 优先检查依赖健康和 server 日志；搜索无结果检查 Elasticsearch 健康与后端回灌；正文 404 检查 `STORAGE_TYPE`、共享卷或 OSS object key。更多可执行命令见 [Docker 操作入口](../../docker/README.md)。

## 发布与回滚边界

- 发布前备份 MySQL、Redis 持久化数据和 `uploads_data`/OSS。Redis bitmap 保存点赞/收藏成员关系，不是全部可重建缓存。
- 应用镜像回滚可通过部署上一已验证版本完成；配置也必须与该版本兼容。
- 数据库 DDL/数据迁移不会随容器回滚。已应用 migration 不可修改；对不可逆变更使用前向修复，或在演练过的条件下恢复备份。
- 不要在不确认数据卷归属时执行 `down -v`。回滚后重新运行健康、Feed、登录、详情和启用功能检查。
