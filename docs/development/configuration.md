# 配置

## 权威来源

| 来源 | 负责范围 |
|------|----------|
| [`.env.example`](../../.env.example) | 本地开发可复制的环境变量示例；根 PowerShell 脚本读取复制后的 `.env` |
| [`.env.prod.example`](../../.env.prod.example) | 生产 Compose 的示例值与必填占位符 |
| [`application.yml`](../../apps/server/src/main/resources/application.yml) | Spring 属性、环境变量映射与代码级缺省值 |
| [`application-dev.yml`](../../apps/server/src/main/resources/application-dev.yml) | `dev` profile：Swagger 与冷启动 seed |
| [`application-llm.yml`](../../apps/server/src/main/resources/application-llm.yml) | `llm` profile：启用 LLM/RAG 并恢复 Spring AI 自动配置 |
| [`agent-domain.yml`](../../apps/server/src/main/resources/agent-domain.yml) | Agent 领域提示词、错误文案、工具描述与模板，不是密钥文件 |
| [`apps/web`](../../apps/web/README.md#环境变量) | Next.js 服务端与 `NEXT_PUBLIC_*` 浏览器变量的消费边界 |

判断实际行为时，先看 `application*.yml` 或前端代码的缺省值，再看当前启动方式是否加载了示例复制出的 `.env`。示例值是推荐运行组合，不等于属性本身的缺省值。

## 关键功能组

### 基础服务与异步链路

- `MYSQL_*`、`REDIS_*`、`ES_URIS` 定位后端依赖；生产 Compose 会把主机名固定为服务名。
- `KAFKA_ENABLED` 在 `application.yml` 中缺省为 `false`，即使用进程内 fallback；两份 `.env` 示例都显式写成 `true`，按示例启动就必须提供 Kafka。这一差异是有意的：属性缺省保证可降级，推荐完整环境覆盖异步链路。
- `CANAL_ENABLED` 缺省及示例均为 `false`。未部署 Canal 时不要仅因存在相关配置就开启。

### 存储、LLM 与外部服务

- `STORAGE_TYPE` 缺省和当前本地/生产示例均为 `local`；路径分别默认 `./uploads` 和 Compose 中的 `/app/uploads`。切换 `oss` 后才需要有效的 `OSS_*`。
- `LLM_ENABLED` 缺省和示例均为 `false`。启用时需 `llm` profile、`DEEPSEEK_API_KEY` 与 `DASHSCOPE_API_KEY`；推荐根脚本会按开关补充 profile，裸 Maven 不会。
- `BANGUMI_ENABLED` 缺省为 `true`，但访问令牌、代理和同步任务分别控制受限接口与后台同步。
- `application-dev.yml` 默认开启 Swagger 和冷启动 seed；生产示例关闭 Swagger，且 Compose 当前固定 `LLM_ENABLED=false`、`CANAL_ENABLED=false`，修改示例 `.env` 本身不会覆盖这些固定值。

### Agent 领域配置

运行参数如最大步数、记忆 TTL 与工具超时由 `application.yml` 的 `agent.*` 映射环境变量；面向领域的提示词、错误信息和 Bangumi 展示模板集中在 `agent-domain.yml`。修改模板时应验证占位符与 [`AgentDomainConfig`](../../apps/server/src/main/java/com/chtholly/agent/config/AgentDomainConfig.java) 的绑定，避免把运行密钥或机器地址写入该文件。

### Next.js 环境边界

- `API_SERVER_URL` 只在 Next 服务端与 rewrite 使用；浏览器代码不可读取未加 `NEXT_PUBLIC_` 的变量。
- `NEXT_PUBLIC_API_SERVER_URL`、`NEXT_PUBLIC_WS_URL`、`NEXT_PUBLIC_SITE_URL`、`NEXT_PUBLIC_OWNER_USER_ID` 等会进入浏览器构建产物，不得放密钥。
- 根脚本可把根 `.env` 注入启动进程；裸 `npm run dev`/`build` 只读取应用目录的 `.env.local` 等 Next 文件或现有进程环境。

## 密钥与变更纪律

只提交 `.env.example` / `.env.prod.example` 的空值或安全占位符，不提交 `.env`、`apps/web/.env.local`、访问令牌、数据库密码或云凭据。新增环境变量时同时更新消费配置、对应示例与相关文档；不要在多个 README 复制完整变量表。
