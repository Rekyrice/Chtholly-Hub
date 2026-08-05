# 单机生产部署配置修正 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前单机生产部署修正为默认低成本、可选 Kafka、可直接启用 LLM，并提供不入 Git 的安全配置包生成工具。

**Architecture:** 以现有 `docker-compose.prod.yml` 为唯一受支持入口，Kafka 通过 Compose profile 选择；生产环境变量和外部 JWT 密钥由 Git 忽略的配置包注入。部署脚本只负责首次空库初始化和健康检查，seed 与 HTTPS 都必须显式启用。

**Tech Stack:** Docker Compose、Bash、PowerShell 7/.NET RSA、Node.js `node:test`、Nginx、Spring Boot 3.2。

---

### Task 1: 固化生产配置契约

**Files:**
- Create: `scripts/deploy/production-config.test.mjs`
- Modify: `package.json`（若根目录没有测试入口则不修改，直接运行 Node 测试）

- [ ] **Step 1: 写失败测试**

测试读取 Compose、环境变量样例、初始化脚本与忽略规则，断言：Kafka 使用 profile；默认开关为 false；LLM/JWT 变量被传入；ES 副本为 0；seed 仅由 `--with-seed` 启用；本地配置和密钥目录被忽略。

- [ ] **Step 2: 确认 RED**

Run: `node --test scripts/deploy/production-config.test.mjs`

Expected: FAIL，指出 Kafka 无 profile、LLM 被硬编码关闭或忽略目录不存在。

- [ ] **Step 3: 保留测试作为后续配置最小契约**

测试只检查可观察部署行为，不解析或复制任何真实密钥。

### Task 2: 修正 Compose 与生产环境变量样例

**Files:**
- Modify: `docker-compose.prod.yml`
- Modify: `.env.prod.example`
- Modify: `.gitignore`

- [ ] **Step 1: 将 Kafka 改为可选 profile**

为 `kafka` 添加 `profiles: ["kafka"]` 并固定镜像版本；后端对 Kafka 使用非必需依赖，默认 `KAFKA_ENABLED=false`、`CANAL_ENABLED=false`。

- [ ] **Step 2: 透传 LLM、Agent 与所有者变量**

向 server 环境加入 `SPRING_PROFILES_ACTIVE`、`LLM_ENABLED`、`DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`、Agent 超时/步数/记忆参数、OWNER 参数和 Bangumi 参数。

- [ ] **Step 3: 挂载 JWT 外部密钥**

只读挂载 `.production-secrets/jwt-private.pem` 与 `jwt-public.pem`，设置 `AUTH_JWT_PRIVATE_KEY=file:/run/secrets/jwt-private.pem` 和对应公钥变量。

- [ ] **Step 4: 修正样例默认值和忽略规则**

设置 `SPRING_PROFILES_ACTIVE=llm`、`LLM_ENABLED=true`、`ES_REPLICAS=0`、`KAFKA_ENABLED=false`、`COMPOSE_PROFILES=`，加入 `.local-deploy/` 与 `.production-secrets/`。

- [ ] **Step 5: 确认 GREEN**

Run: `node --test scripts/deploy/production-config.test.mjs`

Expected: PASS。

### Task 3: 让首次部署和数据库初始化安全失败

**Files:**
- Modify: `scripts/deploy/ecs-bootstrap.sh`
- Modify: `scripts/deploy/ecs-init-db.sh`
- Test: `scripts/deploy/production-config.test.mjs`

- [ ] **Step 1: 扩展失败测试**

断言两个脚本均不包含 `source .env`；初始化脚本检测非空数据库；只有 `--with-seed` 分支导入 `phase_a_seed.sql`。

- [ ] **Step 2: 确认 RED**

Run: `node --test scripts/deploy/production-config.test.mjs`

Expected: FAIL，指出脚本仍执行 `.env` 或无条件 seed。

- [ ] **Step 3: 最小修改脚本**

使用容器内 `MYSQL_ROOT_PASSWORD`/`MYSQL_DATABASE` 执行 mysql 命令；bootstrap 在构建前运行配置预检，根据 `SEED_PHASE_A=true` 决定是否传 `--with-seed`。

- [ ] **Step 4: 验证脚本和契约**

Run: `node --test scripts/deploy/production-config.test.mjs`

Run: `"D:/1.hhh/Application/git/Git/bin/bash.exe" -n scripts/deploy/ecs-bootstrap.sh scripts/deploy/ecs-init-db.sh`

Expected: 两条命令均 PASS。

### Task 4: 增加 HTTPS 安全切换入口

**Files:**
- Modify: `docker/nginx/default.conf`
- Create: `docker/nginx/https.conf.template`
- Create: `scripts/deploy/ecs-enable-https.sh`
- Modify: `docker-compose.prod.yml`
- Test: `scripts/deploy/production-config.test.mjs`

- [ ] **Step 1: 写失败测试**

断言 HTTP 配置提供 ACME webroot，Compose 挂载证书和 challenge 目录，HTTPS 脚本在切换前检查证书并运行 `nginx -t`。

- [ ] **Step 2: 确认 RED**

Run: `node --test scripts/deploy/production-config.test.mjs`

Expected: FAIL，指出 ACME/HTTPS 文件缺失。

- [ ] **Step 3: 实现最小 HTTPS 流程**

保持首次 HTTP 可启动；证书签发后由脚本渲染域名、安装 HTTPS 配置并在容器内 `nginx -t` 成功后 reload。

- [ ] **Step 4: 验证**

Run: `node --test scripts/deploy/production-config.test.mjs`

Run: `"D:/1.hhh/Application/git/Git/bin/bash.exe" -n scripts/deploy/ecs-enable-https.sh`

Expected: PASS。

### Task 5: 生成不入 Git 的本地生产配置工具

**Files:**
- Create (ignored): `.local-deploy/prepare-production.ps1`
- Generate (ignored): `.local-deploy/production-bundle/.env`
- Generate (ignored): `.local-deploy/production-bundle/.production-secrets/jwt-private.pem`
- Generate (ignored): `.local-deploy/production-bundle/.production-secrets/jwt-public.pem`
- Generate (ignored): `.local-deploy/production-bundle/install.sh`

- [ ] **Step 1: 写本地脚本自测入口**

脚本支持 `-NonInteractive -Domain example.test -OutputDirectory <ignored-temp>`，供测试在不显示密钥的情况下生成临时 bundle。

- [ ] **Step 2: 实现安全读取与生成**

逐行解析本地主工作区 `.env`；复用已配置 LLM/Agent 值；生成 hex 数据库密码、RSA 3072 密钥和安装脚本；日志只显示变量名和文件路径。

- [ ] **Step 3: 验证本地脚本未被跟踪**

Run: `git check-ignore -v .local-deploy/prepare-production.ps1 .local-deploy/production-bundle/.env .production-secrets/jwt-private.pem`

Expected: 三条路径均命中 `.gitignore`。

- [ ] **Step 4: 解析和无交互自测**

Run: PowerShell parser 检查脚本无语法错误；随后生成到 `.codex-tmp/prod-config-test/`，验证文件存在、私钥不是本地仓库现有密钥的拷贝，并确认输出不含 API key。

### Task 6: 更新部署文档并完成验证

**Files:**
- Modify: `docs/operations/deployment.md`
- Modify: `docker/README.md`
- Modify: `scripts/README.md`

- [ ] **Step 1: 更新低成本、Kafka、LLM、首次 DB 与 HTTPS 命令**

明确配置包生成、上传、安装、启动、证书签发、验证、升级与回滚边界。

- [ ] **Step 2: 运行配置验证**

Run: `node --test scripts/deploy/production-config.test.mjs`

Run: `docker compose -f docker-compose.prod.yml --env-file .env.prod.example config --format json`

Run: `docker compose -f docker-compose.prod.yml --env-file .env.prod.example --profile kafka config --format json`

- [ ] **Step 3: 运行项目验证**

Run: `cd apps/server && mvn test`

Run: `cd apps/web && npm run test:run && npm run build`

Run: `git diff --check && git status --short`

- [ ] **Step 4: 提交前忽略审计**

Run: `git diff --cached --name-only --diff-filter=A | git check-ignore -v --no-index --stdin`

Expected: 无输出。
