package com.chtholly.counter.service.impl;

import com.chtholly.counter.mapper.CounterReactionMapper;
import com.chtholly.counter.service.CounterService;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.relation.mapper.RelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCounterServiceImplTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private PostMapper postMapper;
    @Mock
    private CounterService counterService;
    @Mock
    private RelationMapper relationMapper;
    @Mock
    private CounterReactionMapper reactionMapper;

    private UserCounterServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserCounterServiceImpl(
                redis,
                postMapper,
                counterService,
                relationMapper,
                reactionMapper);
    }

    @Test
    void invalidationIsIdempotentAndReceivedCountsComeFromMysqlFacts() {
        when(reactionMapper.countPostReactionsReceived(10L, "like")).thenReturn(7L);
        when(reactionMapper.countPostReactionsReceived(10L, "fav")).thenReturn(4L);

        service.invalidateReactionCounters(10L);
        service.invalidateReactionCounters(10L);

        verify(redis, org.mockito.Mockito.times(2))
                .delete(List.of("ucnt:10", "ucnt:chk:10"));
        assertThat(service.countLikesReceived(10L)).isEqualTo(7L);
        assertThat(service.countFavsReceived(10L)).isEqualTo(4L);
    }
}
