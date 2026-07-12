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

Chtholly Hub 仓库负责 Web 体验、业务 API、后台任务与容器化配置。浏览器只直接访问 Next.js 或同域代理；Spring Boot 承担业务规则并访问 MySQL、Redis、Elasticsearch、Kafka 和 OSS。外部 LLM、Embedding 与 Bangumi 等服务通过可选集成接入，不是主站阅读与基础互动的启动前提。

MySQL 是业务事实的最终来源；Redis 保存可重建缓存和部分高频状态；Elasticsearch 保存可重建搜索索引；Kafka 传递异步事件；OSS 保存 Markdown 正文与媒体对象。各存储的一致性和降级边界见[数据与存储](data-and-storage.md)。

## 组件关系

```text
浏览器
  │
  ▼
Next.js（页面渲染、交互、同域 API 代理）
  │
  ▼
Spring Boot（认证、内容、社区、搜索、后台任务）
  ├──▶ MySQL（业务权威数据）
  ├──▶ Redis（缓存、Token、限流、位图状态）
  ├──▶ Elasticsearch（可降级全文检索）
  ├──▶ Kafka（异步聚合与 Outbox）
  └──▶ OSS（正文与媒体）

可选分支：
浏览器 ──WebSocket/API──▶ Agent ──▶ LLM
                              ├──▶ RAG / Embedding
                              └──▶ 站内搜索、Bangumi 等工具
```

## 同步与异步边界

- 页面渲染、认证、文章与评论读写等用户需要立即确认结果的操作，经 Next.js 到 Spring Boot 同步完成。
- MySQL 写入定义业务提交边界；缓存失效、搜索索引或计数汇总不得反向成为业务事实来源。
- Redis 命中可缩短读链路，未命中时回源 MySQL；缓存不可用时由具体领域决定降级或失败策略。
- Kafka 承担点赞/收藏等计数聚合及 Outbox 下游处理，使非关键派生更新脱离主请求；消费端必须考虑重试和幂等。
- Spring ApplicationEvent 用于进程内通知等轻量协作；它与 Kafka 的可靠性和跨进程边界不同。
- Agent 的 WebSocket/API 调用在会话内流式返回，LLM、RAG 与工具调用属于可选分支；关闭相关特性不应阻断博客与社区主链路。

## 章节导航

- [后端架构](backend.md)：业务领域、包边界、主要入口与依赖。
- [前端架构](frontend.md)：用户路径、路由、组件与 Server/Client 边界。
- [Agent 系统](agent-system.md)：Agent Core、上下文、工具、记忆与可选扩展。
- [数据与存储](data-and-storage.md)：MySQL、Redis、Kafka、Elasticsearch、OSS 的职责。
- [请求链路](request-flows.md)：高价值端到端调用、状态位置与失败路径。
- [开发入口](../development/README.md)：本地环境、配置、数据库和测试。
- [部署文档](../operations/deployment.md)：生产拓扑、验证与回滚边界。

## 关键入口

| 关注点 | 代码或操作入口 |
|--------|----------------|
| Next.js 应用 | [`apps/web`](../../apps/web/README.md) |
| Spring Boot 应用 | [`apps/server`](../../apps/server/README.md) |
| 后端启动类 | `apps/server/src/main/java/com/chtholly/ChthollyApplication.java` |
| 前端站点路由 | `apps/web/app/(site)` |
| 数据库 schema 与 migration | [`apps/server/db`](../../apps/server/db/README.md) |
| 生产容器与代理 | [`docker`](../../docker/README.md)、`docker-compose.prod.yml` |
| 开发与运维脚本 | [`scripts`](../../scripts/README.md) |

具体类名、配置与测试入口由各专章维护，本页只定义全局关系，避免复制易漂移的实现清单。
