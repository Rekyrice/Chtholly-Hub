package com.chtholly.bangumi.client;

import com.chtholly.bangumi.config.BangumiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BangumiClientTest {

    @Mock
    private BangumiProperties properties;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private RedissonClient redisson;
    @Mock
    private RRateLimiter rateLimiter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BangumiClient client;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getBaseUrl()).thenReturn("https://api.bgm.tv");
        lenient().when(properties.getUserAgent()).thenReturn("Test/1.0");
        lenient().when(properties.getRatePerSecond()).thenReturn(1);
        when(redisson.getRateLimiter(anyString())).thenReturn(rateLimiter);

        client = new BangumiClient(properties, restTemplate, redisson, objectMapper);
        client.initRateLimiter();
    }

    @Test
    void initRateLimiterOnlyOnce() {
        verify(rateLimiter, times(1)).trySetRate(RateType.OVERALL, 1, 1, RateIntervalUnit.SECONDS);
    }

    @Test
    void retriesOnServerError() {
        AtomicInteger calls = new AtomicInteger();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenAnswer(inv -> {
                    if (calls.incrementAndGet() < 3) {
                        throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "boom");
                    }
                    return ResponseEntity.ok(objectMapper.createObjectNode().put("ok", true));
                });

        Optional<JsonNode> result = client.getSubject(1L);

        assertThat(result).isPresent();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void doesNotRetryOn404() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND, "missing", null, null, StandardCharsets.UTF_8));

        Optional<JsonNode> result = client.getSubject(999L);

        assertThat(result).isEmpty();
        verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    void doesNotRetryOn429() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS, "rate limited", null, null, StandardCharsets.UTF_8));

        Optional<JsonNode> result = client.getSubject(1L);

        assertThat(result).isEmpty();
        verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    void retriesOnNetworkTimeout() {
        AtomicInteger calls = new AtomicInteger();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenAnswer(inv -> {
                    if (calls.incrementAndGet() < 2) {
                        throw new ResourceAccessException("timeout", new ConnectException("refused"));
                    }
                    return ResponseEntity.ok(objectMapper.createObjectNode());
                });

        Optional<JsonNode> result = client.getSubject(2L);

        assertThat(result).isPresent();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void acquiresRateLimitPerRequest() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(objectMapper.createObjectNode()));

        client.getSubject(1L);
        client.getSubject(2L);

        verify(rateLimiter, times(2)).acquire(1);
        verify(rateLimiter, times(1)).trySetRate(RateType.OVERALL, 1, 1, RateIntervalUnit.SECONDS);
    }
}
