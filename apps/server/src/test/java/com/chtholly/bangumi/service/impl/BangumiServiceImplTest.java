package com.chtholly.bangumi.service.impl;

import com.chtholly.bangumi.client.BangumiClient;
import com.chtholly.bangumi.mapper.BangumiSubjectMapper;
import com.chtholly.bangumi.model.BangumiSubjectRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BangumiServiceImplTest {

    @Mock
    private BangumiSubjectMapper subjectMapper;
    @Mock
    private BangumiClient bangumiClient;

    private ObjectMapper objectMapper;
    private TestTransactionManager transactionManager;
    private BangumiServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        transactionManager = new TestTransactionManager();
        service = new BangumiServiceImpl(subjectMapper, bangumiClient, objectMapper, transactionManager);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void search_returnsLocalHitsWithoutCallingBangumiApi() {
        BangumiSubjectRow local = new BangumiSubjectRow();
        local.setId(1L);
        local.setName("Local Hit");
        when(subjectMapper.searchByKeyword("re0", 5)).thenReturn(List.of(local));

        List<BangumiSubjectRow> rows = service.search("re0", 5);

        assertThat(rows).hasSize(1);
        verify(bangumiClient, never()).searchSubjects(anyString(), anyInt());
    }

    @Test
    void search_doesNotHoldTransactionDuringHttp() {
        when(subjectMapper.searchByKeyword("foo", 5)).thenReturn(List.of());
        when(subjectMapper.searchByKeywordLike("foo", 5)).thenReturn(List.of());

        ObjectNode resp = buildSubjectSearchResponse(100L, "Test Anime");
        AtomicBoolean httpOutsideTransaction = new AtomicBoolean(false);
        when(bangumiClient.searchSubjects(eq("foo"), eq(5))).thenAnswer(inv -> {
            httpOutsideTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
            return resp;
        });
        when(bangumiClient.listEpisodes(100L, 1)).thenReturn(objectMapper.createObjectNode().put("total", 12));
        when(subjectMapper.findById(100L)).thenReturn(null);

        service.search("foo", 5);

        assertThat(httpOutsideTransaction).isTrue();
        verify(subjectMapper).upsert(any(BangumiSubjectRow.class));
    }

    @Test
    void search_apiUnavailable_usesFriendlyMessage() {
        when(subjectMapper.searchByKeyword(anyString(), anyInt())).thenReturn(List.of());
        when(subjectMapper.searchByKeywordLike(anyString(), anyInt())).thenReturn(List.of());
        when(bangumiClient.searchSubjects(anyString(), anyInt())).thenReturn(null);

        IllegalStateException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.search("foo", 5), IllegalStateException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).isEqualTo("Bangumi 服务暂时不可用，请稍后再试。");
        assertThat(ex.getMessage()).doesNotContain("VPN", "代理", ".env");
    }

    @Test
    void search_httpTimeout_doesNotLeaveTransactionOpen() {
        when(subjectMapper.searchByKeyword(anyString(), anyInt())).thenReturn(List.of());
        when(subjectMapper.searchByKeywordLike(anyString(), anyInt())).thenReturn(List.of());

        when(bangumiClient.searchSubjects(anyString(), anyInt())).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Thread.sleep(50);
            return null;
        });

        int txBefore = transactionManager.getBeginCount();
        assertThatThrownBy(() -> service.search("timeout", 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("暂时不可用");

        assertThat(transactionManager.getBeginCount() - txBefore).isLessThanOrEqualTo(1);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private ObjectNode buildSubjectSearchResponse(long id, String name) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", id);
        item.put("type", 2);
        item.put("name", name);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.putArray("data").add(item);
        return resp;
    }

    /** 测试用轻量事务管理器，跟踪 begin 次数并维护 Spring 事务同步状态。 */
    private static final class TestTransactionManager implements PlatformTransactionManager {
        private final AtomicInteger beginCount = new AtomicInteger();

        int getBeginCount() {
            return beginCount.get();
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            beginCount.incrementAndGet();
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.initSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(true);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            TransactionSynchronizationManager.clear();
        }

        @Override
        public void rollback(TransactionStatus status) {
            TransactionSynchronizationManager.clear();
        }
    }
}
