package com.chtholly.health;

import org.springframework.boot.actuate.health.Health;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** 健康检查公共工具：限制单次探测最长 3 秒，避免阻塞 actuator 线程。 */
public final class HealthCheckSupport {

    /** 单次健康探测超时（秒）。 */
    public static final int TIMEOUT_SECONDS = 3;

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private HealthCheckSupport() {
    }

    public static Health runWithTimeout(Supplier<Health> check) {
        try {
            return CompletableFuture.supplyAsync(check, EXECUTOR)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return Health.down()
                    .withDetail("error", "health check timeout after " + TIMEOUT_SECONDS + "s")
                    .build();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) {
                return Health.down().withException(ex).build();
            }
            return Health.down().withDetail("error", cause.getMessage()).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("error", "health check interrupted").build();
        }
    }
}
