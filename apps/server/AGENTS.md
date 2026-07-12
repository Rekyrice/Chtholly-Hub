# Chtholly Hub 后端局部规则

本文件适用于 `apps/server` 子树；仓库级安全与 Git 规则继续以根目录 [`AGENTS.md`](../../AGENTS.md) 为准。

## 开始前

1. 先读[后端领域地图](../../docs/architecture/backend.md)，确认所属领域、入口与联动面。
2. 涉及 MySQL、Redis、Kafka、Elasticsearch 或文件存储时，再读[数据与存储](../../docs/architecture/data-and-storage.md)和[数据库](../../docs/development/database.md)。
3. 端到端行为跨越多个领域时，继续读[核心请求链路](../../docs/architecture/request-flows.md)。

## 代码结构与注释

- 业务模块优先按 `{module}/api`、`service`、`service/impl`、`mapper`、`model` 分层；新增类型放到职责最近的现有包，不创建含义重叠的通用层。
- 类级 Javadoc 使用英文，说明职责、系统位置与关键协作关系。
- `public`/`protected` 方法级 Javadoc 使用英文，并按需要写明 `@param`、`@return`、`@throws`。
- 方法体内只为非显然设计写中文 WHY 注释；魔法数字和非常规逻辑必须说明含义或来源，不给显而易见代码加注释。

## 错误、配置与持久化

- 业务错误使用 `BusinessException` 与明确 HTTP 状态；禁止 `catch (Exception ignored) {}`，每个 catch 至少记录日志。
- 环境相关配置写入 `src/main/resources/application*.yml`，使用 `${VAR:default}`；可选组件必须由显式特性开关控制。
- MyBatis 参数只使用 `#{param}`，禁止 `${param}`；Mapper XML 位于 `src/main/resources/mapper`。
- 新实体 ID 使用 `SnowflakeIdGenerator`，禁止新增自增主键。

## 测试与验证

- 单元和组件测试位于 `src/test/java`，集成测试位于 `src/test/java/com/chtholly/integration`，测试资源位于 `src/test/resources`。
- 修改领域逻辑时，优先运行对应测试类；修改配置、事件或存储边界时补充相关集成测试。

```powershell
cd apps/server

# 全量单元测试
mvn test

# 定向测试；PowerShell 中多个类名参数必须整体加引号
mvn -q '-Dtest=ClassATest,ClassBTest' test

# Testcontainers 集成测试
mvn -Pintegration-test verify
```

命令职责、外部依赖和 CI 边界见[测试与验证](../../docs/development/testing.md)；运行入口见 [`README.md`](README.md)，环境变量权威来源见[配置](../../docs/development/configuration.md)。
