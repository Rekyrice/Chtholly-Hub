# Chtholly Hub 后端运行入口

`apps/server` 是基于 Java 21、Spring Boot 3.2.4、MyBatis 与 Maven 的业务 API 和后台任务应用。

## 前置条件

- JDK 21 与 Maven。
- MySQL、Redis 是基础依赖；仓库示例 `.env` 显式设置 `KAFKA_ENABLED=true`，按推荐流程启动时也需准备 Kafka，除非将该值改为 `false` 使用进程内 fallback。职责与可靠性边界见[数据与存储](../../docs/architecture/data-and-storage.md)。
- 在仓库根目录从 `.env.example` 创建本地 `.env`，不要把凭据写入文档或提交到 Git。
- 首次初始化数据库时阅读 [`db/README.md`](db/README.md)。

## 本地启动

推荐从仓库根目录使用脚本启动；脚本会加载根目录 `.env`，而直接运行 Spring Boot 不会自动读取该文件。

```powershell
# 仓库根目录
./scripts/dev/start-backend.ps1
```

默认地址为 `http://localhost:8888`，健康检查为 `http://localhost:8888/actuator/health`。以下直接启动命令仅适用于环境变量已经由外部注入到当前终端的情况；它不会加载根目录 `.env`：

```powershell
cd apps/server
mvn spring-boot:run
```

## 核心配置入口

- [`application.yml`](src/main/resources/application.yml)：默认端口、数据源、Redis、Kafka、Elasticsearch、存储和特性开关。
- [`application-dev.yml`](src/main/resources/application-dev.yml)：开发 profile 覆盖。
- [`application-llm.yml`](src/main/resources/application-llm.yml)：LLM profile 覆盖。
- [根目录 `.env.example`](../../.env.example)：可配置环境变量清单。
- Agent Core、扩展开关与 `LLM_ENABLED` 的当前边界见[后端领域地图的 Agent 平台章节](../../docs/architecture/backend.md#agent-平台)。

## 编译与测试

```powershell
cd apps/server

mvn clean compile '-Dmaven.test.skip=true'
mvn test
mvn -q '-Dtest=ClassATest,ClassBTest' test
mvn -Pintegration-test verify
```

## 继续阅读

- [后端局部规则](AGENTS.md)
- [后端领域地图](../../docs/architecture/backend.md)
- [数据与存储](../../docs/architecture/data-and-storage.md)
- [核心请求链路](../../docs/architecture/request-flows.md)
- [数据库操作入口](db/README.md)
