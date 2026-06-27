# Chtholly Hub — Agent 指南

> 本文件是参与本项目开发的 Coding Agent 的**唯一权威参考**。
> 开始任何任务前，**务必先阅读本文**。

## 项目概览

Chtholly Hub 是一个动漫博客向社区演进的全栈平台，从个人动漫博客起步，逐步引入 AI 内容能力。

- **GitHub**: https://github.com/Rekyrice/Chtholly-Hub
- **技术栈**: Java 21 + Spring Boot 3.2.4（后端）/ Next.js 16 + Tailwind CSS 4（前端）
- **架构**: Monorepo — `apps/server`（Spring Boot）+ `apps/web`（Next.js）

## 快速开始

```bash
# 环境变量：复制仓库根目录 .env.example 为 .env 并填写

# 后端
cd apps/server
mvn spring-boot:run    # 默认 http://localhost:8888

# 前端
cd apps/web
npm install
npm run dev            # http://localhost:3000，/api/* 代理到 :8888

# 基础设施（Docker）
docker compose -f docker-compose.prod.yml up -d
```

## 模块地图

### 后端（`apps/server/src/main/java/com/chtholly/`）

| 包名 | 职责 | 关键文件 |
|------|------|----------|
| `auth` | RS256 JWT 双 Token 认证、短信验证码 | `SecurityConfig`, `AuthService`, `JwtService` |
| `post` | 文章 CRUD、多级缓存（Caffeine L1 + Redis L2）、SingleFlight、热 Key 检测 | `PostServiceImpl`, `PostFeedServiceImpl`, `HotKeyDetector` |
| `counter` | 分布式点赞/收藏/浏览计数 — Redis Bitmap + SDS + Kafka 异步聚合 | `CounterServiceImpl`, `CounterAggregationConsumer` |
| `relation` | 关注/取关 — 双表模型、Outbox + Canal CDC | `RelationServiceImpl`, `CanalOutboxConsumer` |
| `comment` | 二级嵌套评论 + 通知事件 | `CommentServiceImpl`, `CommentController` |
| `notification` | 事件驱动通知（Spring ApplicationEvent） | `NotificationServiceImpl`, `NotificationEventListener` |
| `tag` | 标签 CRUD、文章关联、使用计数 | `TagServiceImpl`, `TagController` |
| `search` | Elasticsearch 全文搜索（IK 分词）、游标分页 | `SearchServiceImpl`, `SearchIndexService`, `SearchIndexInitializer` |
| `bangumi` | Bangumi API 客户端、本地缓存、番剧数据同步 | `BangumiClient`, `BangumiServiceImpl`, `BangumiSyncJob` |
| `agent` | 自研 ReAct Agent 引擎 + WebSocket 流式输出 | `ChthollyAgent`, `AgentTool`, `AgentWebSocketHandler` |
| `agent/tools` | Agent 工具：BangumiSearch、ArticleRAG、FulltextSearch 等 | `*Tool.java` |
| `agent/memory` | 内存对话记忆（计划迁移 Redis） | `AgentConversationMemory`, `AgentMemoryStore` |
| `storage` | OSS 预签名上传、头像管理 | `OssStorageService`, `StorageController` |
| `profile` | 用户资料编辑、头像上传 | `ProfileServiceImpl`, `ProfileController` |
| `user` | 公开用户信息、用户主页 | `UserPublicServiceImpl`, `UserController` |
| `health` | 自定义 Actuator Health Indicator（ES/OSS/Bangumi） | `ElasticsearchHealthIndicator`, `OssHealthIndicator`, `BangumiHealthIndicator` |
| `common` | 全局异常处理、雪花 ID、Slug 工具、分页 | `GlobalExceptionHandler`, `SnowflakeIdGenerator`, `SlugUtils` |
| `config` | Elasticsearch、Redis、Redisson、Kafka、线程池配置 | `*Config.java` |
| `llm` | Spring AI 集成、RAG 索引、SSE 流式 | `RagQueryService`, `PostRagIndexer` |

### 前端（`apps/web/`）

| 目录 | 职责 |
|------|------|
| `app/(site)/` | 公开页：首页、文章详情、归档、标签、关于、搜索、用户主页、Agent |
| `app/(site)/login/` | 手机号 + 短信验证码登录/注册 |
| `app/(site)/write/` | Markdown 编辑器 + 五步发布流程 |
| `components/site/` | 共享 UI：Navbar、Sidebar、PostCard、Footer、CommentSection、NotificationBell |
| `components/agent/` | Agent 聊天界面（AgentChat.tsx） |
| `lib/services/` | API 客户端与服务模块（auth、post、comment、search、tag、notification、storage、user） |
| `lib/types/` | TypeScript 类型定义 |
| `lib/auth/` | 客户端 JWT Token 管理 |

### 数据库

- **基础 Schema**: `apps/server/db/schema.sql`
- **迁移脚本**: `apps/server/db/migration/V5__tags.sql` 至 `V10__notification_cleanup_index.sql`
- **种子数据**: `apps/server/db/seed/phase_a_seed.sql`
- **ORM**: MyBatis XML，`apps/server/src/main/resources/mapper/`

## 编码规范

### 后端（Java）

1. **包结构**: `{module}/api/`（Controller + DTO）、`{module}/service/`（接口）、`{module}/service/impl/`（实现）、`{module}/mapper/`（MyBatis）、`{module}/model/`（实体/Row）
2. **注释分层策略**（类/方法英文门面，方法体内中文 WHY）:
   - **(a) 类级 Javadoc → 英文**：描述职责、在系统中的位置、关键架构关系；可含 `@see` 引用相关类
   - **(b) 方法级 Javadoc → 英文**：public/protected 方法写 `@param`、`@return`、`@throws`（API 文档性质）
   - **(c) 方法体内 WHY 注释 → 中文**：解释**为什么**这样设计（面试时可当讲解），不复述代码在做什么
   - **(d) 魔法数字与非常规逻辑**：必须有行内注释（中英文皆可），说明含义或来源
   - **(e) TODO/FIXME/HACK 标记**:
     - `// TODO: Description — tracked in [issue/prompt reference]`
     - `// HACK: 临时方案，原因 [reason]，在 [condition] 后移除`
   - **不做的事**: 不给 Lombok 生成的 getter/setter 加注释；不给显而易见代码加注释；不写被注释掉的死代码
   - 示例:
     ```java
     /**
      * Multi-level cache feed service for public post listings.
      *
      * <p>Architecture: Caffeine L1 → Redis L2 fragment cache → MySQL.
      * Uses SingleFlight for stampede prevention.
      *
      * @see HotKeyDetector
      */
     public class PostFeedServiceImpl {
         /**
          * Fetches a page of public posts with multi-level cache.
          *
          * @param page Page number (1-indexed).
          * @param size Items per page (max 50).
          * @return Cached feed page with enriched post items.
          */
         public FeedPage getPublicFeed(int page, int size) {
             // 用 SingleFlight 而不是直接查缓存，是因为缓存同时过期时
             // 几十个并发请求会同时打到 DB（缓存击穿）。
         }
     }
     ```
3. **异常处理**: 使用 `BusinessException` + HTTP 状态码。**禁止** `catch (Exception ignored) {}`，每个 catch 至少记录日志
4. **配置**: 环境相关值统一 `${VAR:default}` 写在 `application.yml`；可选模块用特性开关（`LLM_ENABLED`、`CANAL_ENABLED`）
5. **SQL**: MyBatis 一律 `#{param}` 参数化，**禁止** `${param}`（SQL 注入）
6. **ID**: 实体 ID 统一 `SnowflakeIdGenerator`，禁止自增主键

### 前端（TypeScript/React）

1. **默认 Server Components**：`app/(site)/` 下页面为服务端组件，ISR（`revalidate = 60`）；仅交互必需时使用 `"use client"`
2. **API 调用**: Server Component 直接调 `postService.*`；Client Component 用 `lib/services/apiClient.ts`
3. **样式**: Tailwind 工具类 + `globals.css` 自定义属性；主题色 `--blog-primary`、`--blog-secondary`
4. **类型**: 所有 API 请求/响应类型定义在 `lib/types/`

### Git

1. **Commit 格式**: Conventional Commits — `feat:`、`fix:`、`refactor:`、`chore:`、`docs:`、`test:`
2. **Commit 语言**: 中文
3. **分支命名**: `feat/{description}`、`fix/{description}`

## 架构决策

| 决策 | 理由 |
|------|------|
| 多级缓存（Caffeine + Redis） | L1 热 Key（纳秒级）、L2 温数据（毫秒级）、DB 为最终数据源 |
| SingleFlight | 防止缓存击穿 — 同一 pageKey 并发只打一次 DB |
| Outbox + Canal CDC | 写路径与搜索索引/关系同步解耦，无需 2PC |
| 通知用 Spring ApplicationEvent（非 Kafka） | MVP 刻意简化 — 通知非关键路径，同步可接受 |
| 自研 ReAct Agent（非 Spring AI Agent） | 核心循环约 50 行，完全可控，无框架负担 |
| MySQL 邻接表做知识图谱（非 Neo4j） | 数据量小（数千节点），Java BFS 比 Cypher 更清晰 |
| Redis Bitmap 做点赞/收藏幂等 | O(1) 查/写，bitcount 统计 — 极省内存 |

## 已知技术债

> 详细执行计划见 `docs/prompts/` 下各改进文档（若尚未创建，以 Issue/规划文档为准）。

- **Agent**: `docs/prompts/agent-improvements.md`
- **Backend**: `docs/prompts/backend-improvements.md`
- **Frontend**: `docs/prompts/frontend-improvements.md`

## 部署

- **生产环境**: 单机 ECS + Docker Compose
- **Compose 文件**: `docker-compose.prod.yml`（Spring Boot + Next.js standalone + MySQL + Redis + Nginx）
- **Nginx**: `docker/nginx/default.conf` — `/api` 反代 Spring Boot，静态走 Next.js
- **OSS**: 阿里云 OSS 存储 Markdown 与头像
- **降级路径**: 去掉 Kafka → Spring Event；去掉 ES → MySQL LIKE（见规划文档）

## 环境变量

开发见仓库根目录 `.env.example`，生产见 `.env.prod.example`。常用变量：

| 变量 | 用途 | 默认值 |
|------|------|--------|
| `SERVER_PORT` | 后端端口 | `8888` |
| `MYSQL_HOST` | 数据库主机 | `localhost` |
| `REDIS_HOST` | Redis 主机 | `localhost` |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 地址 | `localhost:9092` |
| `ES_URIS` | Elasticsearch 集群 | `http://localhost:9200` |
| `DEEPSEEK_API_KEY` | DeepSeek LLM API Key | （无） |
| `DASHSCOPE_API_KEY` | DashScope Embedding API Key | （无） |
| `LLM_ENABLED` | 启用 LLM/Agent | `false` |
| `OSS_*` | 阿里云 OSS 凭证 | （无） |
| `BANGUMI_ACCESS_TOKEN` | Bangumi API Personal Token | （无） |
