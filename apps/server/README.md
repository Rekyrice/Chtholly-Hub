# Chtholly Hub — Backend (`apps/server`)

Spring Boot 3.2 · Java 21 · MyBatis · MySQL `chtholly`

## 模块概览

| 包 | 能力 |
|----|------|
| `auth` | JWT 双令牌、验证码登录 |
| `knowpost` | 帖子 CRUD、Feed、详情 |
| `storage` | OSS 预签名直传 |
| `counter` | 点赞/收藏计数 |
| `relation` | 关注/粉丝 |
| `search` | Elasticsearch 搜索 |
| `llm` | AI 摘要、RAG |

## 本地启动

1. 确认 Docker 中 MySQL / Redis / Kafka / ES 已运行（见仓库 `docker/README.md`）
2. 在 Monorepo 根目录配置 `.env`（从 `.env.example` 复制）
3. 启动（需将环境变量注入进程，或在 IDE 中配置）：

```powershell
cd apps/server
$env:MYSQL_PASSWORD="你的密码"
# 可选：$env:OSS_ACCESS_KEY_ID=... 等
mvn spring-boot:run
```

默认端口：`http://localhost:8080`  
健康检查：`GET /actuator/health`

## 配置说明

主要环境变量见根目录 `.env.example`。数据库名必须为 **`chtholly`**。

Phase A 默认 `CANAL_ENABLED=false`，无需 Canal 即可启动。

## 测试

```powershell
mvn test
```

## 参考

业务代码包名保留 `com.tongji`（迁入来源）。对外产品名统一为 **Chtholly Hub**。
