package com.chtholly.post.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostOutboxProjectionProcessorTransactionTest {

    private AnnotationConfigApplicationContext context;
    private RecordingTransactionManager transactions;
    private PostProjectionReceiptMapper receiptMapper;
    private PostOutboxProjectionService projectionService;
    private PostOutboxProjectionProcessor processor;

    @BeforeEach
    void setUp() {
        transactions = new RecordingTransactionManager();
        receiptMapper = mock(PostProjectionReceiptMapper.class);
        projectionService = mock(PostOutboxProjectionService.class);
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean("transactionManager", PlatformTransactionManager.class, () -> transactions);
        context.registerBean(PostProjectionReceiptMapper.class, () -> receiptMapper);
        context.registerBean(PostOutboxProjectionService.class, () -> projectionService);
        context.registerBean(PostOutboxProjectionProcessor.class);
        context.refresh();
        processor = context.getBean(PostOutboxProjectionProcessor.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void processorIsSpringTransactionalAndCommitsTheCursorAdvance() {
        when(receiptMapper.insertCursorIfAbsent(42L)).thenReturn(1);
        when(receiptMapper.lockCursor(42L)).thenReturn(100L);
        when(receiptMapper.insertReceipt(101L, 42L)).thenReturn(1);
        when(receiptMapper.advanceCursor(42L, 100L, 101L)).thenReturn(1);

        processor.process(101L, "PostPublished", 42L);

        assertThat(AopUtils.isAopProxy(processor)).isTrue();
        assertThat(transactions.commits()).isEqualTo(1);
        assertThat(transactions.rollbacks()).isZero();
    }

    @Test
    void projectionFailureRollsBackWithoutAdvancingTheCursor() {
        when(receiptMapper.insertCursorIfAbsent(42L)).thenReturn(1);
        when(receiptMapper.lockCursor(42L)).thenReturn(100L);
        doThrow(new IllegalStateException("RAG unavailable"))
                .when(projectionService).project("PostPublished", 42L);

        assertThatThrownBy(() -> processor.process(101L, "PostPublished", 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RAG unavailable");

        assertThat(transactions.commits()).isZero();
        assertThat(transactions.rollbacks()).isEqualTo(1);
        verify(receiptMapper, never()).insertReceipt(101L, 42L);
        verify(receiptMapper, never()).advanceCursor(42L, 100L, 101L);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }

        int commits() {
            return commits.get();
        }

        int rollbacks() {
            return rollbacks.get();
        }
    }
}
