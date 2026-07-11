# 后端测试套件维护性优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不修改生产代码、不删除业务场景的前提下，消除测试中的固定等待和已确认的日志噪声，使并发与异步测试更确定、更易维护。

**Architecture:** 保持现有 Surefire/Failsafe 与 Testcontainers 分层不变，只在 `src/test` 内用未完成 Future、CountDownLatch、Awaitility 和 Mockito timeout 表达真实完成条件。日志降噪仅在明确测试调用窗口内临时调整目标 logger 级别，并在 `finally` 恢复；不创建全局测试日志配置。

**Tech Stack:** Java 21、JUnit 5、AssertJ、Mockito、Awaitility、Spring Boot Test、Logback。

---

### Task 1: 消除纯超时模式测试中的后台休眠

**Files:**
- Modify: `apps/server/src/test/java/com/chtholly/agent/AgentLlmTimeoutTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/agent/AgentToolParamValidatorTest.java`

- [ ] **Step 1: 运行现有定向测试，记录行为基线**

Run:

```powershell
mvn -q '-Dtest=AgentLlmTimeoutTest,AgentToolParamValidatorTest' test
```

Expected: 7 tests pass；两个超时测试分别等待约 1 秒。

- [ ] **Step 2: 用未完成 Future 表达超时条件**

将两个测试中的后台 `supplyAsync` 和 `Thread.sleep` 替换为未完成 Future，并将等待上限缩短为 20ms：

```java
CompletableFuture<String> pending = new CompletableFuture<>();

assertThatThrownBy(() -> pending.get(20, TimeUnit.MILLISECONDS))
        .isInstanceOf(TimeoutException.class);
```

`AgentLlmTimeoutTest` 继续执行：

```java
pending.cancel(true);
assertThat(pending).isCancelled();
```

删除不再需要的异步 lambda，不新增生产辅助类。

- [ ] **Step 3: 运行定向测试验证行为等价**

Run:

```powershell
mvn -q '-Dtest=AgentLlmTimeoutTest,AgentToolParamValidatorTest' test
```

Expected: 7 tests pass，无 `Thread.sleep`。

- [ ] **Step 4: 提交独立改动**

```powershell
git add apps/server/src/test/java/com/chtholly/agent/AgentLlmTimeoutTest.java apps/server/src/test/java/com/chtholly/agent/AgentToolParamValidatorTest.java
git commit -m "test: 移除超时模式测试中的固定休眠"
```

### Task 2: 用完成条件验证异步 Agent、WebSocket 与通知行为

**Files:**
- Modify: `apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/agent/ws/AgentWebSocketHandlerTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/bangumi/service/impl/BangumiServiceImplTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/notification/config/NotificationAsyncConfigurationTest.java`

- [ ] **Step 1: 运行现有定向测试，确认基线通过**

Run:

```powershell
mvn -q '-Dtest=ChthollyAgentTest,AgentWebSocketHandlerTest,BangumiServiceImplTest,NotificationAsyncConfigurationTest' test
```

Expected: 23 tests pass。

- [ ] **Step 2: 让 Agent 超时测试使用可释放信号**

在 `given_llmSlow_when_run_then_timeoutHandled` 中使用 `CountDownLatch releaseLlm` 阻塞 Mockito Answer：

```java
CountDownLatch releaseLlm = new CountDownLatch(1);
when(callSpec.content()).thenAnswer(invocation -> {
    if (!releaseLlm.await(3, TimeUnit.SECONDS)) {
        throw new AssertionError("LLM timeout guard did not release blocked call");
    }
    return "{\"action\":\"final\",\"answer\":\"late\"}";
});

try {
    agent.run("超时测试", 1L, null, events::add);
} finally {
    releaseLlm.countDown();
}
```

保留原有 error 事件断言；该测试仍覆盖真实的一秒生产超时，但不依赖 1500ms 经验值。

- [ ] **Step 3: 让 WebSocket 测试等待可观察结果**

在 `rateLimitsChatAfterTenMessages` 中将异步写入集合替换为线程安全集合：

```java
List<String> payloads = new CopyOnWriteArrayList<>();
```

然后用 Awaitility 等待断言成立：

```java
Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
    long rateLimited = payloads.stream()
            .filter(payload -> payload.contains("RATE_LIMITED"))
            .count();
    assertThat(rateLimited).isGreaterThanOrEqualTo(5);
    verify(agent, atLeast(10)).run(any(), anyLong(), any(), any(), any(), any());
});
```

在 `clearMessageDoesNotCountTowardRateLimit` 中用 Mockito 完成条件替换休眠：

```java
verify(agent, timeout(2_000)).run(any(), anyLong(), any(), any(), any(), any());
```

- [ ] **Step 4: 删除 Bangumi 测试中与断言无关的等待**

`search_httpTimeout_doesNotLeaveTransactionOpen` 的 Mockito Answer 已在调用线程内同步执行。保留事务断言并直接返回：

```java
when(bangumiClient.searchSubjects(anyString(), anyInt())).thenAnswer(invocation -> {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    return Optional.empty();
});
```

- [ ] **Step 5: 让通知执行器测试等待任务完成并确定性关闭**

创建 `CountDownLatch completed = new CountDownLatch(1)`，任务结束时 `countDown()`，主线程使用有上限的 `await`：

```java
CountDownLatch completed = new CountDownLatch(1);
try {
    executor.execute(() -> {
        asyncId.set(MDC.get(CorrelationIdSupport.MDC_CORRELATION_ID));
        completed.countDown();
    });
    assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(asyncId.get()).isEqualTo("notif-async");
} finally {
    executor.shutdown();
}
```

- [ ] **Step 6: 运行定向测试并提交**

Run:

```powershell
mvn -q '-Dtest=ChthollyAgentTest,AgentWebSocketHandlerTest,BangumiServiceImplTest,NotificationAsyncConfigurationTest' test
```

Expected: 23 tests pass；相关文件不再包含固定 `sleep`。

Commit:

```powershell
git add apps/server/src/test/java/com/chtholly/agent/ChthollyAgentTest.java apps/server/src/test/java/com/chtholly/agent/ws/AgentWebSocketHandlerTest.java apps/server/src/test/java/com/chtholly/bangumi/service/impl/BangumiServiceImplTest.java apps/server/src/test/java/com/chtholly/notification/config/NotificationAsyncConfigurationTest.java
git commit -m "test: 使用完成信号验证异步行为"
```

### Task 3: 让 SingleFlight 测试真正证明并发语义

**Files:**
- Modify: `apps/server/src/test/java/com/chtholly/cache/singleflight/SingleFlightLockRegistryTest.java`

- [ ] **Step 1: 运行现有测试并记录绿灯基线**

Run:

```powershell
mvn -q '-Dtest=SingleFlightLockRegistryTest' test
```

Expected: 3 tests pass。

- [ ] **Step 2: 让 leader 持锁并观测同 Key followers 阻塞**

使用固定大小为 10 的 `ExecutorService`，通过自定义 `ThreadFactory` 将工作线程记录到线程安全列表。先提交 leader Future；leader 首次进入 action 后触发 `leaderEntered`，并在有界的 `releaseLeader` 上持有 SingleFlight monitor。确认 leader 已进入后再提交 9 个 follower Future，并用 Awaitility 有界等待至少 9 个记录线程处于 `Thread.State.BLOCKED`：

```java
futures.add(executor.submit(worker));
assertThat(leaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
for (int i = 0; i < 9; i++) {
    futures.add(executor.submit(worker));
}
await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
        assertThat(workerThreads.stream()
                .filter(thread -> thread.getState() == Thread.State.BLOCKED)
                .count()).isGreaterThanOrEqualTo(9));
releaseLeader.countDown();
```

主线程对 leader 和所有 follower 执行有界 `Future.get`，断言均返回 `loaded`，使 worker 异常显式传播到 JUnit。`finally` 中始终释放 leader，并执行 `shutdownNow` 与有界 `awaitTermination`，避免失败路径泄漏线程。

- [ ] **Step 3: 用 Executor/Future 和双向屏障证明不同 Key 并行**

为 `differentKeysRunInParallel` 使用固定大小为 2 的 `ExecutorService`，两个 Callable 的 action 共享 `CountDownLatch enteredActions = new CountDownLatch(2)`。每个 action 先 `countDown()`，再有界等待另一个 action 同时进入：

```java
enteredActions.countDown();
if (!enteredActions.await(2, TimeUnit.SECONDS)) {
    throw new IllegalStateException("different keys were serialized");
}
calls.incrementAndGet();
```

主线程对两个 Future 执行有界 `get`，将 action 异常显式传播到 JUnit；`finally` 中关闭执行器并有界等待终止。如果实现误用全局锁，第一个 action 会以 `different keys were serialized` 超时失败。

- [ ] **Step 4: 运行定向测试并提交**

Run:

```powershell
mvn -q '-Dtest=SingleFlightLockRegistryTest' test
```

Expected: 3 tests pass，文件中不再包含 `Thread.sleep`。

Commit:

```powershell
git add apps/server/src/test/java/com/chtholly/cache/singleflight/SingleFlightLockRegistryTest.java
git commit -m "test: 强化 SingleFlight 并发语义验证"
```

### Task 4: 定向收敛测试日志噪声

**Files:**
- Modify: `apps/server/src/test/java/com/chtholly/agent/anchor/AnchorManagerTest.java`
- Modify: `apps/server/src/test/java/com/chtholly/seed/SeedOrchestratorTest.java`
- Modify: `apps/server/TESTING_OPTIMIZATION_PLAN.md`

- [ ] **Step 1: 复现已确认的预期日志**

Run:

```powershell
$output = & mvn -q '-Dtest=AnchorManagerTest,SeedOrchestratorTest' test 2>&1
$output | Select-String 'Identity anchor failed','Seed dry-run summary'
```

Expected: 输出包含 AnchorManager 的预期失败堆栈和 SeedOrchestrator 的 dry-run INFO。

- [ ] **Step 2: 仅对明确预期日志的调用局部调级**

不创建测试级 Logback 配置，避免 Spring Context 重载日志系统时产生全局覆盖和执行顺序耦合。

- `AnchorManagerTest.buildContextFallsBackPerAnchorWithoutCascadeFailure`：仅在一次预期 fallback 调用期间将 `AnchorManager` logger 临时设为 `ERROR`。
- `SeedOrchestratorTest`：仅两个 dry-run 测试通过私有辅助方法调用 `orchestrator.run(...)`，调用期间将 `SeedOrchestrator` logger 临时设为 `WARN`；其他七个测试继续直接调用。

两处均保存原 level，以 `try/finally` 包住目标调用并在 `finally` 恢复；断言在恢复后执行，异常路径也不会泄漏 logger 状态。

- [ ] **Step 3: 验证定向与全量测试均无目标噪声**

Run:

```powershell
$targetOutput = & mvn -q '-Dtest=AnchorManagerTest,SeedOrchestratorTest' test 2>&1
if ($LASTEXITCODE -ne 0) { throw 'targeted tests failed' }
if ($targetOutput -match 'Identity anchor failed|Seed dry-run summary') { throw 'targeted test noise remains' }

$fullOutput = & mvn -q '-Dspring.profiles.active=test' test 2>&1
if ($LASTEXITCODE -ne 0) { throw 'full tests failed' }
if ($fullOutput -match 'Identity anchor failed|Seed dry-run summary') { throw 'full test noise remains' }
if ($fullOutput -notmatch ' WARN ') { throw 'non-target WARN logs were hidden' }
```

Expected: 定向 12 tests 与全量 439 tests 均通过；两个目标字符串在全量输出中均为 0，Bangumi 等非目标 WARN 仍可见。

- [ ] **Step 4: 提交局部日志降噪改动**

```powershell
git add apps/server/src/test/java/com/chtholly/agent/anchor/AnchorManagerTest.java `
        apps/server/src/test/java/com/chtholly/seed/SeedOrchestratorTest.java `
        apps/server/TESTING_OPTIMIZATION_PLAN.md
git commit -m "test: 收敛预期异常日志噪声"
```

### Task 5: 全量验证范围与行为等价

**Files:**
- Verify: `apps/server/src/test/**`
- Verify unchanged: `apps/server/src/test/java/com/chtholly/integration/**`
- Verify unchanged: `apps/server/pom.xml`
- Verify unchanged: `.github/workflows/ci.yml`

- [ ] **Step 1: 确认集成测试与执行配置没有被修改**

Run from repository root:

```powershell
git diff --exit-code origin/main -- apps/server/src/test/java/com/chtholly/integration apps/server/pom.xml .github/workflows/ci.yml
```

Expected: exit code 0，无输出。

- [ ] **Step 2: 运行后端全量快速测试**

Run:

```powershell
cd apps/server
mvn -q '-Dspring.profiles.active=test' test
```

Expected: 439 tests、0 failures、0 errors、1 skipped。

- [ ] **Step 3: 检查残余固定等待**

Run from repository root:

```powershell
Get-ChildItem apps/server/src/test -Recurse -Filter '*.java' |
    Select-String -Pattern 'Thread\.sleep','TimeUnit\..*sleep'
```

Expected: 只允许本轮未纳入的显式测试场景；本计划修改的七个测试文件中无匹配。

- [ ] **Step 4: 检查提交范围与空白错误**

Run:

```powershell
git status --short
git diff --check origin/main...HEAD
git log --left-right --cherry-pick --oneline origin/main...HEAD
```

Expected: 工作区干净；diff check 通过；仅包含设计、计划和四个测试维护提交。
