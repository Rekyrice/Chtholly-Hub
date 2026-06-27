package com.chtholly.bangumi.client;

import com.chtholly.bangumi.config.BangumiProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bangumi v0 API 客户端（Redisson 全局限流 + 可重试 HTTP）。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bangumi.enabled", havingValue = "true", matchIfMissing = true)
public class BangumiClient {

    private static final String RATE_LIMITER_KEY = "bangumi:api:global";
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1000L;

    private final BangumiProperties properties;
    private final RestTemplate bangumiRestTemplate;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;

    private RRateLimiter rateLimiter;

    @PostConstruct
    void initRateLimiter() {
        rateLimiter = redisson.getRateLimiter(RATE_LIMITER_KEY);
        int rate = Math.max(1, properties.getRatePerSecond());
        rateLimiter.trySetRate(RateType.OVERALL, rate, 1, RateIntervalUnit.SECONDS);
    }

    /** 获取每日放送表。 */
    public Optional<JsonNode> fetchCalendar() {
        return exchangeJson(properties.getBaseUrl() + "/calendar", HttpMethod.GET, null);
    }

    /** 搜索条目。 */
    public Optional<JsonNode> searchSubjects(String keyword, int limit) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.getBaseUrl() + "/v0/search/subjects")
                .queryParam("limit", Math.min(Math.max(limit, 1), 25))
                .queryParam("offset", 0)
                .toUriString();

        Map<String, Object> body = Map.of("keyword", keyword, "sort", "match");
        return exchangeJson(url, HttpMethod.POST, body);
    }

    /** 搜索人物（作者/漫画家/声优等）。 */
    public Optional<JsonNode> searchPersons(String keyword, List<String> careers, int limit) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.getBaseUrl() + "/v0/search/persons")
                .queryParam("limit", Math.min(Math.max(limit, 1), 25))
                .queryParam("offset", 0)
                .toUriString();

        Map<String, Object> body = new HashMap<>();
        body.put("keyword", keyword);
        if (careers != null && !careers.isEmpty()) {
            body.put("filter", Map.of("career", careers));
        }
        return exchangeJson(url, HttpMethod.POST, body);
    }

    /** 条目关联人物（含 staff 角色，如「漫画」）。 */
    public Optional<JsonNode> getSubjectPersons(long subjectId) {
        String url = properties.getBaseUrl() + "/v0/subjects/" + subjectId + "/persons";
        return exchangeJson(url, HttpMethod.GET, null);
    }

    /** 条目关联角色（登场人物）。 */
    public Optional<JsonNode> getSubjectCharacters(long subjectId) {
        String url = properties.getBaseUrl() + "/v0/subjects/" + subjectId + "/characters";
        return exchangeJson(url, HttpMethod.GET, null);
    }

    /** 人物参与的全部条目。 */
    public Optional<JsonNode> getPersonSubjects(long personId) {
        String url = properties.getBaseUrl() + "/v0/persons/" + personId + "/subjects";
        return exchangeJson(url, HttpMethod.GET, null);
    }

    /** 获取条目详情。 */
    public Optional<JsonNode> getSubject(long subjectId) {
        String url = properties.getBaseUrl() + "/v0/subjects/" + subjectId;
        return exchangeJson(url, HttpMethod.GET, null);
    }

    /** 获取分集列表（含 total）。 */
    public Optional<JsonNode> listEpisodes(long subjectId, int limit) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.getBaseUrl() + "/v0/episodes")
                .queryParam("subject_id", subjectId)
                .queryParam("limit", Math.min(limit, 50))
                .queryParam("offset", 0)
                .toUriString();
        return exchangeJson(url, HttpMethod.GET, null);
    }

    private Optional<JsonNode> exchangeJson(String url, HttpMethod method, Object body) {
        acquirePermit();

        final HttpEntity<?> entity;
        try {
            entity = buildEntity(body);
        } catch (JsonProcessingException e) {
            log.error("Bangumi 请求体序列化失败 {} {}: {}", method, url, e.getMessage(), e);
            return Optional.empty();
        }

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                ResponseEntity<JsonNode> resp = bangumiRestTemplate.exchange(url, method, entity, JsonNode.class);
                return Optional.ofNullable(resp.getBody());
            } catch (HttpClientErrorException e) {
                log.warn("Bangumi API 客户端错误 {} {} -> {}: {}", method, url, e.getStatusCode().value(), e.getMessage());
                return Optional.empty();
            } catch (HttpServerErrorException e) {
                if (attempt < MAX_RETRIES) {
                    log.info("Bangumi API 服务端错误，第 {}/{} 次重试 {} {} -> {}: {}",
                            attempt + 1, MAX_RETRIES, method, url, e.getStatusCode().value(), e.getMessage());
                    sleepBeforeRetry();
                    continue;
                }
                log.warn("Bangumi API 服务端错误，已达最大重试 {} {} -> {}: {}",
                        method, url, e.getStatusCode().value(), e.getMessage());
                return Optional.empty();
            } catch (ResourceAccessException e) {
                if (isRetryableNetwork(e) && attempt < MAX_RETRIES) {
                    log.info("Bangumi API 网络异常，第 {}/{} 次重试 {} {}: {}",
                            attempt + 1, MAX_RETRIES, method, url, e.getMessage());
                    sleepBeforeRetry();
                    continue;
                }
                log.warn("Bangumi API 网络异常 {} {}: {}", method, url, e.getMessage());
                return Optional.empty();
            } catch (Exception e) {
                log.warn("Bangumi API 调用失败 {} {}: {}", method, url, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private HttpEntity<?> buildEntity(Object body) throws JsonProcessingException {
        HttpHeaders headers = buildHeaders(body != null);
        if (body == null) {
            return new HttpEntity<>(headers);
        }
        return new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
    }

    private HttpHeaders buildHeaders(boolean jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", properties.getUserAgent());
        if (StringUtils.hasText(properties.getAccessToken())) {
            headers.setBearerAuth(properties.getAccessToken().trim());
        }
        if (jsonBody) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return headers;
    }

    private void acquirePermit() {
        rateLimiter.acquire(1);
    }

    private static boolean isRetryableNetwork(ResourceAccessException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof ConnectException || t instanceof SocketTimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return true;
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
