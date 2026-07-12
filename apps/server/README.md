# Chtholly Hub — Backend (`apps/server`)

Spring Boot 3.2 · Java 21 · MyBatis · MySQL `chtholly`

## 模块概览

| 包 | 能力 |
|----|------|
| `auth` | JWT 双令牌、验证码登录 |
| `post` | 帖子 CRUD、Feed、详情 |
| `storage` | OSS 预签名直传 |
| `counter` | 点赞/收藏计数 |
| `relation` | 关注/粉丝 |
| `search` | Elasticsearch 搜索 |
| `llm` | AI 摘要、RAG（需 `LLM_ENABLED=true`） |

## 本地启动

1. 确认 Docker 中 MySQL / Redis / Kafka / ES 已运行（见仓库 `docker/README.md`）
2. 首次开发需完成 [db/README.md](db/README.md) 中的 schema + seed + OSS 正文上传
3. 在 Monorepo 根目录配置 `.env`（从 `.env.example` 复制）
4. 启动（Spring Boot **不会**自动读取根目录 `.env`，推荐用 `scripts/dev/start-backend.ps1`）：

```powershell
cd apps/server
$env:MYSQL_PASSWORD="你的密码"
$env:CANAL_ENABLED="false"   # Phase A 无需 Canal
$env:LLM_ENABLED="false"     # Phase A 无需 AI Key
mvn spring-boot:run "-Dmaven.test.skip=true"
```

默认端口：`http://localhost:8888`

### 健康检查与 Phase A API 冒烟

```powershell
Invoke-RestMethod http://localhost:8888/actuator/health
Invoke-RestMethod "http://localhost:8888/api/v1/posts/feed?ownerId=1&page=1&size=10"
Invoke-RestMethod http://localhost:8888/api/v1/posts/detail/by-slug/welcome-chtholly-hub
```

预期：health 为 `UP`；Feed 返回 3 条种子帖子；详情含 `slug` 与 `contentUrl`。

## 配置说明

主要环境变量见根目录 `.env.example`。数据库名必须为 **`chtholly`**。

| 变量 | Phase A 默认 | 说明 |
|------|-------------|------|
| `CANAL_ENABLED` | `false` | 关闭 Canal 桥接 |
| `LLM_ENABLED` | `false` | 关闭 DeepSeek / Embedding / RAG |
| `SITE_OWNER_USER_ID` | `1` | 站长用户 ID（Feed `ownerId` 过滤） |

启用 LLM/RAG 时：设置 `LLM_ENABLED=true`，配置 `DEEPSEEK_API_KEY` 与 `DASHSCOPE_API_KEY`，并激活 profile `llm`（见 `application-llm.yml`）。

### Agent Core 与可选扩展

Agent Core 负责身份、关系、页面与问题上下文、工具协议，以及有界的模型调用和工具执行循环。内容理解、知识图谱、学习、经验、情绪、社区动作和主动行为属于可选扩展；以下开关均默认启用，并支持 Spring Boot relaxed binding 对应的环境变量。

| Spring 属性 | 环境变量 | 默认值 | 控制范围 |
|-------------|----------|--------|----------|
| `agent.extensions.content.enabled` | `AGENT_EXTENSIONS_CONTENT_ENABLED` | `true` | 内容理解与主题聚类 |
| `agent.extensions.graph.enabled` | `AGENT_EXTENSIONS_GRAPH_ENABLED` | `true` | 知识图谱抽取、索引与查询 |
| `agent.extensions.learning.enabled` | `AGENT_EXTENSIONS_LEARNING_ENABLED` | `true` | 洞察学习与程序性记忆 |
| `agent.extensions.experience.enabled` | `AGENT_EXTENSIONS_EXPERIENCE_ENABLED` | `true` | 经验生成、读取与管理接口 |
| `agent.extensions.mood.enabled` | `AGENT_EXTENSIONS_MOOD_ENABLED` | `true` | 情绪、季节与默认互动策略 |
| `agent.extensions.community-actions.enabled` | `AGENT_EXTENSIONS_COMMUNITY_ACTIONS_ENABLED` | `true` | 评论生成与社区通知动作 |
| `agent.extensions.proactive.enabled` | `AGENT_EXTENSIONS_PROACTIVE_ENABLED` | `true` | 主动触发、受众选择、限流与分发 |

将七个开关全部设为 `false` 可运行 Core-only 模式：Core 仍能构建稳定 Prompt，并通过中立内容契约提供空结果降级，不会装配上述扩展组件。`LLM_ENABLED` 仍独立控制真实模型与 RAG 客户端是否启用，扩展开关不会绕过它。

组合依赖采用整体降级，避免只装配半条调用链：认知引擎要求 `learning + experience` 同时启用；主动行为要求 `proactive + experience + community-actions` 同时启用。任一依赖关闭时，对应组合组件整体不注册。

## 编译

```powershell
mvn clean compile "-Dmaven.test.skip=true"
```

Cursor / VS Code 请安装 **Lombok** 扩展，并启用 `java.jdt.ls.lombokSupport.enabled`。

## 测试

```powershell
mvn test
```

## 参考

后端统一 **`com.chtholly`** 包名；REST **`/api/v1/posts`**；计数实体类型 **`post`**；用户 **`handle`** 字段。对外产品名 **Chtholly Hub**。
