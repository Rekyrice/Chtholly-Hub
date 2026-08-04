package com.chtholly.relation.service.impl;

import com.chtholly.notification.event.FollowCreatedEvent;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.relation.event.FollowCanceledEvent;
import com.chtholly.relation.mapper.RelationMapper;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies relation command failure policy through a real Spring transaction proxy. */
class RelationTransactionProxyTest {

    private AnnotationConfigApplicationContext context;
    private RecordingTransactionManager transactions;
    private RelationMapper relationMapper;
    private OutboxMapper outboxMapper;
    private StringRedisTemplate redis;
    private SnowflakeIdGenerator idGenerator;
    private RelationCommandService service;

    @BeforeEach
    void setUp() {
        transactions = new RecordingTransactionManager();
        relationMapper = mock(RelationMapper.class);
        outboxMapper = mock(OutboxMapper.class);
        redis = mock(StringRedisTemplate.class);
        idGenerator = mock(SnowflakeIdGenerator.class);

        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(
                "transactionManager",
                PlatformTransactionManager.class,
                () -> transactions);
        context.registerBean(RelationMapper.class, () -> relationMapper);
        context.registerBean(OutboxMapper.class, () -> outboxMapper);
        context.registerBean(StringRedisTemplate.class, () -> redis);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(UserMapper.class, () -> mock(UserMapper.class));
        context.registerBean(SnowflakeIdGenerator.class, () -> idGenerator);
        context.registerBean(RelationCommandService.class);
        context.refresh();
        service = context.getBean(RelationCommandService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulFollowCommitsThroughTheSpringProxy() {
        allowFollow();
        when(relationMapper.insertFollowing(101L, 11L, 22L, 1))
                .thenReturn(1);
        when(idGenerator.nextId()).thenReturn(101L, 201L);

        assertThat(AopUtils.isAopProxy(service)).isTrue();
        assertThat(service.follow(11L, 22L)).isTrue();

        assertThat(transactions.begins()).isEqualTo(1);
        assertThat(transactions.commits()).isEqualTo(1);
        assertThat(transactions.rollbacks()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void outboxFailureRollsBackTheCommandTransaction() {
        allowFollow();
        when(relationMapper.insertFollowing(101L, 11L, 22L, 1))
                .thenReturn(1);
        when(idGenerator.nextId()).thenReturn(101L, 201L);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(outboxMapper)
                .insert(
                        eq(201L),
                        eq("following"),
                        eq(101L),
                        eq("FollowCreated"),
                        anyString());

        assertThatThrownBy(() -> service.follow(11L, 22L))
                .isInstanceOf(IllegalStateException.class);

        assertThat(transactions.commits()).isZero();
        assertThat(transactions.rollbacks()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void followCreatedListenerFailureRollsBackTheCommandTransaction() {
        allowFollow();
        when(relationMapper.insertFollowing(101L, 11L, 22L, 1))
                .thenReturn(1);
        when(idGenerator.nextId()).thenReturn(101L, 201L);
        context.addApplicationListener(event -> {
            if (event instanceof PayloadApplicationEvent<?> payload
                    && payload.getPayload() instanceof FollowCreatedEvent) {
                throw new IllegalStateException("listener unavailable");
            }
        });

        assertThatThrownBy(() -> service.follow(11L, 22L))
                .isInstanceOf(IllegalStateException.class);

        assertThat(transactions.commits()).isZero();
        assertThat(transactions.rollbacks()).isEqualTo(1);
    }

    @Test
    void followCanceledListenerFailureIsBestEffortAndStillCommits() {
        when(relationMapper.cancelFollowing(11L, 22L)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(201L);
        context.addApplicationListener(event -> {
            if (event instanceof PayloadApplicationEvent<?> payload
                    && payload.getPayload() instanceof FollowCanceledEvent) {
                throw new IllegalStateException("listener unavailable");
            }
        });

        assertThat(service.unfollow(11L, 22L)).isTrue();

        assertThat(transactions.commits()).isEqualTo(1);
        assertThat(transactions.rollbacks()).isZero();
    }

    @SuppressWarnings("unchecked")
    private void allowFollow() {
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(),
                any())).thenReturn(1L);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }

    private static final class RecordingTransactionManager
            extends AbstractPlatformTransactionManager {

        private final AtomicInteger begins = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition) {
            begins.incrementAndGet();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }

        int begins() {
            return begins.get();
        }

        int commits() {
            return commits.get();
        }

        int rollbacks() {
            return rollbacks.get();
        }
    }
}
