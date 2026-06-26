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
2. 在 Monorepo 根目录配置 `.env`（从 `.env.example` 复制）
3. 启动（Spring Boot **不会**自动读取根目录 `.env`，需注入环境变量或在 IDE 中配置）：

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

## 编译

CLI 需 Lombok 注解处理器（已在 `pom.xml` 配置）。推荐使用 **JDK 21**：

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
