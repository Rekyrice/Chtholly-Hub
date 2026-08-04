# 单机生产部署配置修正设计

## 背景

当前生产 Compose 会无条件启动 Kafka，并让后端等待 Kafka 健康；同时又把 `LLM_ENABLED` 和 `CANAL_ENABLED` 固定为 `false`。这使 2 核 8 GiB 单机在未使用 Kafka 业务链路时仍承担 broker 内存成本，而 `.env` 中配置的 LLM 开关与密钥也不会进入后端容器。

现有首次部署脚本还会通过 `source .env` 执行配置内容，并无条件导入开发阶段 seed。前者会让含 Shell 特殊字符的密钥产生解析风险，后者可能把指向开发 OSS 的正文记录带入生产数据库，最终表现为文章列表存在、正文访问 404。

## 目标

- 默认采用低成本单机模式：MySQL、Redis、Elasticsearch、Spring Boot、Next.js、Nginx 常驻，Kafka/Canal 默认关闭。
- 保留显式启用 Kafka 的完整模式，不删除已有链路。
- 让生产 `.env` 中的 LLM、Agent、站点、存储和所有者配置真正进入后端容器。
- 用容器外 RSA 密钥覆盖镜像内 JWT 测试密钥。
- 首次数据库初始化默认不导入演示 seed，且不执行 `.env`。
- 提供 Git 忽略的本地一键配置工具，复用现有 `.env` 中的 LLM 密钥，生成可上传服务器的安全配置包。
- 为域名上线保留 Nginx ACME challenge 与 HTTPS 切换入口。

## 方案

### Compose 模式

Kafka 服务加入 `kafka` profile。默认 `docker compose up` 不创建 Kafka，后端使用 `KAFKA_ENABLED=false`、`CANAL_ENABLED=false`，计数和互动 Outbox 走项目已有本地事务后适配器。需要 Kafka 时，维护者显式设置 `COMPOSE_PROFILES=kafka` 与 `KAFKA_ENABLED=true`；Canal 仍需在外部 CDC 已验证后单独启用。

Elasticsearch 保留，因为搜索和索引回灌是站点现有核心能力。单节点默认 `ES_REPLICAS=0`，避免无法分配副本导致长期 yellow。

### LLM 与 JWT

后端容器传入 `SPRING_PROFILES_ACTIVE=llm`、`LLM_ENABLED=true`、DeepSeek/DashScope 密钥及 Agent 参数。Compose 不提供真实默认密钥；验证脚本在 LLM 开启时要求两个密钥非空。

JWT 私钥和公钥位于服务器仓库根目录的 `.production-secrets/`，只读挂载到 `/run/secrets/`，通过 Spring Boot 高优先级环境变量 `AUTH_JWT_PRIVATE_KEY` 和 `AUTH_JWT_PUBLIC_KEY` 覆盖 classpath 测试密钥。

### 一键配置包

本地脚本 `.local-deploy/prepare-production.ps1` 不进入 Git。脚本读取主工作区现有 `.env`，只把所需变量复制到 `.local-deploy/production-bundle/.env`，不会输出密钥。脚本用 .NET RSA API 生成 PKCS#8 私钥和 SubjectPublicKeyInfo 公钥，并生成服务器端 `install.sh`。

上传整个 bundle 后，执行 `sudo bash install.sh /opt/chtholly-hub`：安装脚本复制 `.env` 和 `.production-secrets/`，设置 600/644 权限并运行 Compose 配置检查。所有本地输出与服务器密钥目录都由 `.gitignore` 覆盖。

### 数据库初始化

`ecs-init-db.sh` 不再 `source .env`，而是在 MySQL 容器内使用容器环境变量。默认仅允许空库初始化并执行 schema 与 migrations；`--with-seed` 才导入 `phase_a_seed.sql`。已有数据库必须走单独审核的增量迁移流程，脚本检测到业务表后立即停止。

### HTTPS

默认 Nginx HTTP 配置增加 `/.well-known/acme-challenge/` 静态目录挂载。证书签发后使用独立 HTTPS 配置和启用脚本切换；首次部署不提前引用不存在的证书，避免 Nginx 启动失败。

## 安全与失败处理

- `.env`、`.local-deploy/`、`.production-secrets/` 永不提交。
- 配置工具不记录、不回显、不把密钥写进命令行参数。
- LLM 开启但密钥缺失、JWT 文件缺失、密码仍为占位值或 Kafka 开关/profile 不一致时，在构建前失败。
- 数据库初始化遇到非空库时失败，不重放所有 migration，也不自动清理数据卷。
- HTTPS 切换前验证证书文件和 `nginx -t`，失败时继续保留 HTTP 配置。

## 验证

- Node 配置契约测试检查低成本默认、Kafka profile、LLM 透传、JWT 外部挂载、seed opt-in 和忽略规则。
- `docker compose ... config --format json` 验证默认服务集合和 Kafka profile 服务集合。
- Bash `-n`、PowerShell解析器分别验证已提交和本地脚本语法。
- 最终运行后端全量测试、前端全量测试与生产构建。
