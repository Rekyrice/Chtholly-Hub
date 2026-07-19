package com.chtholly.integration;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts successful MySQL counter snapshot materialization statements in one evidence run. */
@Intercepts(@Signature(type = Executor.class, method = "update",
        args = {MappedStatement.class, Object.class}))
final class CounterEvidenceSqlProbe implements Interceptor {

    private static final Set<String> SNAPSHOT_STATEMENTS = Set.of(
            "com.chtholly.counter.mapper.CounterPersistenceMapper.incrementSnapshots",
            "com.chtholly.counter.mapper.CounterPersistenceMapper.replaceReactionSnapshots");

    private final AtomicInteger successfulUpdates = new AtomicInteger();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result = invocation.proceed();
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        if (SNAPSHOT_STATEMENTS.contains(statement.getId())) {
            successfulUpdates.incrementAndGet();
        }
        return result;
    }

    int count() {
        return successfulUpdates.get();
    }

    void reset() {
        successfulUpdates.set(0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        CounterEvidenceSqlProbe counterEvidenceSqlProbe() {
            return new CounterEvidenceSqlProbe();
        }
    }
}
