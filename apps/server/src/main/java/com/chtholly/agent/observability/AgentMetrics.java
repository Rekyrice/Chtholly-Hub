package com.chtholly.agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/** Agent 模块 Micrometer 指标（依赖 actuator 的 MeterRegistry）。 */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class AgentMetrics {

    private final MeterRegistry registry;
    private final Timer executionDuration;
    private final Counter llmCalls;
    private final Timer ttft;
    private final Timer tpot;
    private final AtomicInteger activeWsConnections = new AtomicInteger();

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.executionDuration = Timer.builder("agent.execution.duration")
                .description("Agent 单次执行总耗时")
                .register(registry);
        this.llmCalls = Counter.builder("agent.llm.calls")
                .description("Agent LLM 调用次数")
                .register(registry);
        this.ttft = Timer.builder("agent.llm.ttft")
                .description("Time to First Token：从 Agent 开始到首个 delta 的耗时")
                .register(registry);
        this.tpot = Timer.builder("agent.llm.tpot")
                .description("Time per Output Token：LLM 生成耗时 / 输出 token 数")
                .register(registry);
        registry.gauge("agent.ws.connections.active", activeWsConnections, AtomicInteger::get);
    }

    public void recordExecution(long durationMs, int llmCallCount, Collection<String> tools, String terminatedBy) {
        executionDuration.record(java.time.Duration.ofMillis(durationMs));
        llmCalls.increment(llmCallCount);
        for (String tool : tools) {
            registry.counter("agent.tool.calls", "tool_name", tool == null ? "unknown" : tool).increment();
        }
        if ("error".equals(terminatedBy) || "timeout".equals(terminatedBy) || "max_steps".equals(terminatedBy)) {
            recordError(terminatedBy);
        }
    }

    public void recordError(String type) {
        registry.counter("agent.errors", "type", type == null ? "unknown" : type).increment();
    }

    /** 记录 Time to First Token（毫秒）。 */
    public void recordTtft(long ttftMs) {
        if (ttftMs >= 0) {
            ttft.record(java.time.Duration.ofMillis(ttftMs));
        }
    }

    /** 记录 Time per Output Token（总生成耗时 / 输出 token 数）。 */
    public void recordTpot(long totalGenerationMs, long outputTokens) {
        if (totalGenerationMs > 0 && outputTokens > 0) {
            long msPerToken = Math.max(1, totalGenerationMs / outputTokens);
            tpot.record(java.time.Duration.ofMillis(msPerToken));
        }
    }

    public void wsConnected() {
        activeWsConnections.incrementAndGet();
    }

    public void wsDisconnected() {
        activeWsConnections.updateAndGet(v -> Math.max(0, v - 1));
    }
}
