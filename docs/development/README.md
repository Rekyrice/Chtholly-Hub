# 开发入口

## 阅读时机

第一次在本地运行项目、切换前后端开发方式，或需要判断某项功能依赖哪些外部服务时先读本页。已经能启动项目、只需要查命令或变量时，直接进入[测试与验证](testing.md)、[配置](configuration.md)或[数据库](database.md)。

## 读完能回答的问题

- 本地最少需要哪些工具和外部依赖？
- 为什么推荐从仓库根目录运行 PowerShell 脚本？
- 裸 Maven、裸 Next.js 与根 `.env` 的边界是什么？
- 启动后如何确认服务可用，下一步去哪里查测试、配置和数据初始化？

## 最小工具链与依赖

| 类别 | 最小要求 | 说明 |
|------|----------|------|
| 后端工具链 | JDK 21、Maven 3.9+ | Spring Boot 应用位于 `apps/server` |
| 前端工具链 | Node.js 20.9+、npm | Next.js 应用位于 `apps/web` |
| 推荐本地终端 | Windows PowerShell | 根目录开发脚本负责加载根 `.env` |
| 基础服务 | MySQL 8、Redis | 主站后端的基础依赖 |
| 按配置启用 | Kafka、Elasticsearch、OSS、LLM/Embedding、Bangumi | `.env.example` 显式开启 Kafka；其他能力按功能与开关准备 |

仓库当前没有面向所有开发者的本地基础设施 Compose。维护者机器的现有容器约定与检查方法见 [Docker 操作入口](../../docker/README.md)；不要把该机器路径当作通用前置条件。

## 推荐启动路径

先从根模板创建不会提交的 `.env`，按本机服务修改数据库密码和功能开关。数据库首次初始化见[数据库](database.md)。

```powershell
Copy-Item .env.example .env
```

首次安装前端依赖后，从仓库根目录分别运行：

```powershell
cd apps/web
npm install
cd ../..
.\scripts\dev\start-backend.ps1
.\scripts\dev\start-frontend.ps1
```

两个启动脚本都会调用 `scripts/dev/load-env.ps1` 读取根 `.env`。后端脚本还会加入 `dev` profile、应用待执行数据库增量，再启动 Spring Boot。默认前端地址为 `http://localhost:3000`，后端健康检查为 `http://localhost:8888/actuator/health`。

### 裸命令的环境边界

- 在 `apps/server` 直接执行 `mvn spring-boot:run` 不会读取仓库根 `.env`；变量必须先注入当前进程。
- 在 `apps/web` 直接执行 `npm run dev` 或 `npm run build` 也不会读取父目录的根 `.env`；使用被 Git 忽略的 `apps/web/.env.local`，或由终端/CI 注入。
- `application-dev.yml` 只在 `dev` profile 生效；`application-llm.yml` 只在 `llm` profile 生效。推荐脚本会确保 `dev` 存在，并在 `LLM_ENABLED=true` 时加入 `llm`。

## 继续阅读

- [测试与验证](testing.md)：按改动风险选择后端、Testcontainers、Vitest、构建和文档检查。
- [配置](configuration.md)：变量权威来源、profile、默认值与示例值差异。
- [数据库](database.md)：全量 schema、增量脚本、种子和生产变更边界。
- [生产部署](../operations/deployment.md)：Compose、代理、初始化、验证与回滚。
- [后端应用入口](../../apps/server/README.md)与[前端应用入口](../../apps/web/README.md)：应用级运行细节。
