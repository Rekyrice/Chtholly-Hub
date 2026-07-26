# Chtholly Hub 架构首页

## 阅读时机

在需要确认系统边界、选择修改入口、评估跨组件影响或追踪端到端链路时阅读本页。若任务已明确属于单个领域，可从“章节导航”直接进入专章。

## 读完能回答的问题

- 浏览器请求经过哪些应用与基础设施？
- 哪些组件保存权威数据，哪些只是缓存、索引或异步传输？
- 哪些调用在请求内同步完成，哪些通过 Kafka、事件或后台任务异步完成？
- 角色 Agent、LLM 与 RAG 在何时启用，未启用时是否影响主站？
- 修改后端、前端、数据或部署时应从哪个代码与文档入口开始？

## 系统边界

Chtholly Hub 仓库负责 Web 体验、业务 API、后台任务与容器化配置。浏览器只直接访问 Next.js 或同域代理；Spring Boot 承担业务规则，访问 MySQL、Redis，并按启用功能连接 Elasticsearch、Kafka 等基础设施。正文与媒体默认写入本地文件系统，也可切换到 OSS。外部 LLM、Embedding 与 Bangumi 等服务通过可选集成接入，不是主站阅读与基础互动的启动前提。

MySQL 保存业务最终事实，其中 `counter_reaction` 是点赞/收藏成员关系的唯一业务事实；关系真实变化与 Outbox 在同一事务提交。Redis Bitmap 是带完整性标记、可从 MySQL 重建的在线成员读投影，SDS 与 `counter_snapshot` 是派生计数。Kafka 只承载异步传输和重放，不是事实来源；Kafka 或 Canal 任一关闭时，互动 Outbox 事件在事务提交后经本地适配器进入同一投影处理核心。Redis 还保存可重建缓存和其他高频状态；Elasticsearch 保存可重建搜索索引。`STORAGE_TYPE` 默认 `local`，使用本地文件系统保存 Markdown 正文与媒体；生产环境可选切换为 OSS。当前操作入口见[数据与存储](data-and-storage.md)、[数据库](../development/database.md)与[生产部署](../operations/deployment.md)。

## 组件关系

```text
浏览器
  │
  ▼
Next.js（页面渲染、交互、同域 API 代理）
  │
  ▼
Spring Boot（认证、内容、社区、搜索、后台任务）
  ├──▶ MySQL（业务权威数据，含互动成员关系）
  ├──▶ Redis（缓存、Token、限流、Bitmap/SDS 读投影）
  ├──▶ Elasticsearch（可降级全文检索）
  ├──▶ Kafka（异步聚合与 Outbox）
  └──▶ 本地文件系统（默认）或 OSS（正文与媒体）

可选分支：
浏览器 ──WebSocket/API──▶ Agent ──▶ LLM
                              ├──▶ RAG / Embedding
                              └──▶ 站内搜索、Bangumi 等工具
```

## 同步与异步边界

- 页面渲染、认证、文章与评论读写等用户需要立即确认结果的操作，经 Next.js 到 Spring Boot 同步完成。
- MySQL 写入定义业务提交边界；缓存失效、搜索索引或计数汇总不得反向成为业务事实来源。
- Redis 命中可缩短读链路，未命中时回源 MySQL；缓存不可用时由具体领域决定降级或失败策略。
- 点赞/收藏命令同步提交 MySQL 目标关系与 Outbox，并直接返回事务确定的目标状态，不等待异步投影。只有 `KAFKA_ENABLED=true` 且 `CANAL_ENABLED=true` 时，Canal-compatible Outbox 消息才经 `canal-outbox` 交给互动消费者；任一开关关闭时，`AFTER_COMMIT` 本地适配器先尝试处理，MySQL Outbox 定时回放再恢复提交后未完成的事件。两条路径进入同一个 `REQUIRES_NEW` 处理事务：先锁定 reaction snapshot、登记 Inbox 并计算快照，再批量回查 MySQL 终态和维护 Redis 投影，最后提交 MySQL 事务；关系与 Outbox 已经独立提交，投影允许短暂延迟。
- 浏览量等通用计数仍由 `KAFKA_ENABLED` 选择旧 `counter-events` 或进程内 Spring Event 通道。Spring 属性缺省值是 `false`，仓库示例显式设置 `KAFKA_ENABLED=true`、`CANAL_ENABLED=false`：该组合保留通用 Kafka 能力，但互动 Outbox 使用本地提交后路径。
- 每一条真实关系变化对应的 Outbox 事件都会在核心处理事务提交后尝试发布进程内副作用，包括已落后于当前 snapshot epoch 的事件。`counter_event_inbox.side_effects_published_at` 只在整次同步 Spring 事件发布调用正常返回后记录事件级回执；向发布器传播的失败保持为空，由本地 Outbox 回放再次尝试。它不是逐监听器回执：某个监听器已产生非事务副作用、后续监听器再失败时，重放仍可能重复前者；监听器若自行吞掉内部失败并正常返回，也可能形成缺失副作用而事件级回执仍成功。24 小时 Redis 标记只在回执提交后尽力写入，不会压住仍待发布的事件；该链路不宣称跨 MySQL、Redis、Kafka 原子提交或 exactly-once。
- Agent 的 WebSocket/API 调用在会话内流式返回，LLM、RAG 与工具调用属于可选分支；关闭相关特性不应阻断博客与社区主链路。

## 章节导航

- [**后端领域地图**](backend.md)：业务领域、22 个顶级包边界、主要入口、依赖与代表性测试。
- [**前端架构**](frontend.md)：用户路径、路由、组件、Server/Client、API、主题、Live2D 与验证入口。
- [**Agent 系统**](agent-system.md)：Agent Core、上下文、运行时、工具、记忆、扩展、Trace 与配置边界。
- [**数据与存储**](data-and-storage.md)：MySQL、Redis、Kafka、Elasticsearch、本地文件和 OSS 的权威性、一致性与降级边界。
- [**核心请求链路**](request-flows.md)：十条高价值端到端调用、状态位置、异步边界与失败路径。
- [**开发指南**](../development/README.md)：本地工具链、启动与阅读入口；继续阅读[测试](../development/testing.md)、[配置](../development/configuration.md)和[数据库](../development/database.md)。
- [**生产部署**](../operations/deployment.md)：生产拓扑、代理、初始化、验证与回滚边界。

## 关键入口

| 关注点 | 代码或操作入口 |
|--------|----------------|
| Next.js 应用 | [`apps/web`](../../apps/web/README.md) |
| 前端局部规则 | [`apps/web/AGENTS.md`](../../apps/web/AGENTS.md) |
| Spring Boot 应用 | [`apps/server`](../../apps/server/README.md) |
| 后端局部规则 | [`apps/server/AGENTS.md`](../../apps/server/AGENTS.md) |
| 后端启动类 | `apps/server/src/main/java/com/chtholly/ChthollyApplication.java` |
| 前端站点路由 | `apps/web/app/(site)` |
| 数据库 schema 与 migration | [数据库章节](../development/database.md)、[`apps/server/db`](../../apps/server/db/README.md) |
| 正文与媒体存储 | [`StorageProperties`](../../apps/server/src/main/java/com/chtholly/storage/config/StorageProperties.java) 定义 `storage.type`；默认使用 [`LocalFileStorageService`](../../apps/server/src/main/java/com/chtholly/storage/LocalFileStorageService.java)，可切换 [`OssStorageService`](../../apps/server/src/main/java/com/chtholly/storage/OssStorageService.java) |
| 生产容器与代理 | [生产部署](../operations/deployment.md)、[`docker`](../../docker/README.md)、`docker-compose.prod.yml` |
| 开发与运维脚本 | [`scripts`](../../scripts/README.md) |

具体类名、配置与测试入口由各专章维护，本页只定义全局关系，避免复制易漂移的实现清单。
