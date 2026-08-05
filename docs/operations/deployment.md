# 生产部署

## 本地与生产边界

仓库没有通用的本地开发基础设施 Compose。维护者机器现有 MySQL、Redis、Kafka、Elasticsearch 容器的名称、端口与排障命令记录在 [Docker 操作入口](../../docker/README.md)，它们是本机约定，不是可复制的开发环境定义。

当前受支持的一键生产入口是 [`docker-compose.prod.yml`](../../docker-compose.prod.yml) + Nginx：MySQL、Redis、Elasticsearch、Kafka、Spring Boot、Next.js standalone 与 Nginx 运行在同一 Compose 网络，只有 Nginx 暴露 `${HTTP_PORT:-80}`。默认正文存储为具名卷 `uploads_data`；需要 OSS 时必须同时调整存储配置和凭据。

## 部署前准备

1. 安装 Docker Engine 与 Compose 插件，准备可持久化的宿主机磁盘和备份策略。
2. 将 [`.env.prod.example`](../../.env.prod.example) 复制为不会提交的 `.env`，替换数据库密码、站点域名与实际启用功能的凭据。
3. 确认 80/443、安全组、DNS 与 TLS 方案；确认 MySQL 业务事实、Redis 运行态、上传卷/OSS 的备份边界。
4. 确认互动传输配置成对：当前一键 Compose 固定 `CANAL_ENABLED=false`，因此点赞/收藏使用本地 `AFTER_COMMIT` 路径；另行设置 `KAFKA_ENABLED=true`、`CANAL_ENABLED=true` 时，代码会选择 Kafka 路径。开关不探测 CDC 健康，也不会在运行中自动回退，启用前必须部署并验证可达 CDC。
5. 完整 Kafka 互动模式需预检 `canal-outbox`、`canal-outbox-retry`、`canal-outbox-dlq`，为应用 Kafka 身份配置 CREATE（如需自动建主题）、DESCRIBE、READ、WRITE 与消费组权限，并单独核验 Canal 的 MySQL 复制账号权限。
6. 当前 Redisson 配置使用 `singleServer`，互动投影的多 key Lua 也没有 Redis Cluster hash-tag 合同；部署必须使用已验证的 standalone Redis 拓扑，不得把本章解释为支持 Cluster 或 Sentinel。
7. 在隔离环境验证镜像构建、数据库脚本和健康检查，再操作已有生产数据。

不要把 `.env`、证书私钥、OSS/LLM/Bangumi 密钥提交到仓库或粘贴进日志与文档。

## 受支持的一键路径：生产 Compose + Nginx

从仓库根目录运行：

```bash
cp .env.prod.example .env
# 编辑 .env
docker compose -f docker-compose.prod.yml up -d --build
```

[`docker/nginx/default.conf`](../../docker/nginx/default.conf) 将 `/api/`、Agent WebSocket 和 `/health` 转给 Spring Boot，将 `/uploads/` 读自共享卷，其余请求转给 Next.js。历史 `posts/{id}/content.*` 只允许 `.md`、`.txt`、`.json`，其余后缀和无后缀对象在 alias 之前返回 404；Spring 本地资源处理器保持相同失败关闭规则。文件末尾提供手工证书模板，但 `docker-compose.prod.yml` 默认只挂载 HTTP 配置。

[`ecs-bootstrap.sh`](../../scripts/deploy/ecs-bootstrap.sh) 与 [`ecs-init-db.sh`](../../scripts/deploy/ecs-init-db.sh) 只适用于这条 Nginx 路径：两个脚本都把 Compose 文件硬编码为 `docker-compose.prod.yml`。首次部署可运行：

```bash
bash scripts/deploy/ecs-bootstrap.sh
```

脚本构建并启动服务，调用 `ecs-init-db.sh`，重启后端以触发索引回灌，再检查首页与 Feed。数据库脚本的真实顺序是 `schema.sql` → 当前 `migration/V*.sql` → `phase_a_seed.sql`，不是应用内 Flyway 自动迁移。详细关系与已有库边界见[数据库](../development/database.md)。OSS seed 正文需另行执行 `node scripts/oss/upload-seed-markdown.mjs`。

`V25__counter_reaction.sql` 按干净迁移设计，不会读取旧 Redis Bitmap。需要保留 Redis-only 互动的已有环境不得直接切换：先停止互动写入、生成同一维护窗口的 MySQL/Redis 备份，完成单独评审的一次性有界导入并核对 `counter_reaction`，再启动新应用并从 MySQL 重建 Redis 投影。新链路稳定后不保留双写兼容层。

已有库切换 `V26__counter_reaction_side_effect_receipt.sql` 时不得让旧版本与新版本滚动混跑：先停止旧版本的相关写入、本地回放与 Kafka 消费者，确认不再产生旧格式 Inbox，再依次应用并验证 V26、`V27__dead_letter_replay_state.sql` 与 `V28__post_projection_receipt.sql`。同一维护窗口还必须排空认证流量并停止全部旧应用节点，再执行 `V29__user_refresh_session_epoch.sql`；确认 `users.refresh_session_epoch` 为有符号 `BIGINT NOT NULL DEFAULT 1` 且存量值均为正数后，才能一次性启动新节点。V29 不支持新旧认证实现滚动混部，切换会使已有 refresh session 一次性失效；回滚旧实现前必须先安全失效全部 refresh membership。V26 增加事件级回执列和 Outbox 回放索引，并通过内部 checkpoint 将迁移时已有的 reaction Inbox 全部标记为已发布；这是“防止历史副作用重复补发优先”的保守选择。旧数据没有逐监听器完成证据，因此极窄的“Inbox 已提交、旧进程却在副作用发布前退出”窗口可能被一并视作已完成，迁移不会自动找回这类历史副作用；如业务必须补偿，应在启用清理前基于独立审计结果人工处理。仍无 Inbox 的 reaction Outbox 会被视为未完成工作。V27 扩展死信状态枚举并增加 attempt token/开始/截止时间；V28 创建文章投影完成回执，启动后会按固定高水位重放仍保留且无回执的历史文章 Outbox。文章 Outbox 只有存在回执时才允许清理，父行删除会级联删除回执而保留帖子游标高水位；清理后迟到的旧消息由“事件 ID 不大于游标且父 Outbox 已不存在”识别并幂等跳过，父行仍在而回执缺失的旧事件则必须重跑投影并补回执。新应用启动前必须完成这些 DDL，并观察日志中是否持续出现无效 payload、回执缺行或投影失败。Outbox 保留期清理只删除已有完成回执的 reaction/post 行。

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

还应验证注册、登录、refresh 轮换、密码重置、封禁与解封、详情正文/上传文件、搜索与 Agent 等实际启用功能。注册验证至少覆盖提交后新用户存在且首枚 `mysql:1` membership 可轮换、token 失败时用户回滚且 pending membership 被补偿、审计和事件只在提交后出现；该链路依赖顺序与补偿，不宣称 MySQL/Redis 跨资源原子提交。已有会话在 V29 切换后应按预期重新登录。关注 Feed 应分别验证 Redis timeline 候选路径和 Redis 不可用时的 MySQL active-following 回退，确认 `followers` 文章不会越过当前关系授权；正文确认应确认对象验证阶段不持有数据库事务，绑定阶段才进入独立短事务。执行一次点赞后，确认 `counter_reaction` 与 Outbox 已提交，随后 `counter_event_inbox`、`counter_snapshot`、`side_effects_published_at` 和 Redis 完整投影收敛；在本地模式重启应用并确认未完成 Outbox 可恢复。文章 Outbox 清理后还应投递一个事件 ID 不大于帖子游标的旧消息，确认它被识别为迟到重复且不会重做投影；父 Outbox 仍存在但回执缺失的旧事件则应重跑投影并补回执。完整 CDC/Kafka 模式还要确认 `counter-reaction-outbox` 及 retry consumer group 没有持续 lag，并监控 `dead_letter_messages` 的 `DEAD`、长期 `REPLAYING` 与 `UNCERTAIN`。完整 Kafka 模式不会同时装配 MySQL 本地回放；第三次重试失败并进入 `DEAD` 后，自动恢复已经结束。排除根因后，通过后端管理员 API `POST /api/v1/admin/dead-letters/{id}/replay` 显式重放；现有管理页不是过期 claim 恢复与证据核对的完整操作界面。接口以唯一 attempt token 抢占 `DEAD → REPLAYING`，用数据库时钟记录开始时间，并按 producer 的 `max.block.ms + delivery.timeout.ms + 30 秒` 写恢复截止时间。HTTP 只等待 10 秒：拿到非空 broker `SendResult` 的终态回调以同 token 改为 `PENDING`，同步发送失败尽力恢复 `DEAD`，异步失败或空结果改为 `UNCERTAIN`；HTTP 超时或中断不取消 producer future，行保持 `REPLAYING` 并由稍后的回调收敛。若进程退出或终态状态写入失败导致行长期停在 `REPLAYING`，先停止并隔离可能恢复执行的旧应用实例，等待 `replay_deadline_at` 到期，再从管理员列表响应读取当前 `replayAttemptToken`，调用 `POST .../{id}/recover-expired?attemptToken={replayAttemptToken}` 仅将同一代 claim 转为 `UNCERTAIN`。该恢复接口不发送消息，也不直接开放重试。核对 broker 与 consumer 证据后，再调用 `POST .../{id}/resolve?attemptToken={replayAttemptToken}&published=true|false` 明确为 `PENDING` 或退回 `DEAD`；该接口只接受 token 匹配的 `UNCERTAIN`，不会改动正在发送的 `REPLAYING` 或普通自动重试 `RETRYING`。token 是并发代际标识而非授权凭证；broker 回调、过期恢复和人工 resolve 都由 token CAS 约束，旧回调与延迟人工请求不能覆盖新一轮 claim。token 不能撤回已经发给 broker 的消息，因此过期恢复前的旧实例隔离仍是强制操作前提。broker 健康不代表 Outbox CDC 已接通。首页 502 优先检查依赖健康和 server 日志；搜索无结果检查 Elasticsearch 健康与后端回灌；正文 404 检查 `STORAGE_TYPE`、共享卷或 OSS object key。更多可执行命令见 [Docker 操作入口](../../docker/README.md)。

## 发布与回滚边界

- 发布前备份 MySQL、Redis 持久化数据和 `uploads_data`/OSS。MySQL `counter_reaction` 保存点赞/收藏成员事实；Redis Bitmap/SDS 可以从 MySQL 重建，RDB 仍用于缩短其他缓存、安全状态和运行态的恢复时间。
- 应用镜像回滚可通过部署上一已验证版本完成；配置也必须与该版本兼容。
- 数据库 DDL/数据迁移不会随容器回滚。已应用 migration 不可修改；对不可逆变更使用前向修复，或在演练过的条件下恢复备份。
- 恢复 Redis RDB 后，以 MySQL 关系重新校准互动投影；不得让旧 RDB 反向覆盖更新的 `counter_reaction`。
- 重建以 live shard 工作：开始后投影保持不完整，只有 Redis prepared、MySQL 新 epoch/绝对计数已提交且 fence 未被并发事件标脏时才发布完整标记。不要通过手工写完整标记跳过恢复步骤。
- 不要在不确认数据卷归属时执行 `down -v`。回滚后重新运行健康、Feed、登录、详情和启用功能检查。
