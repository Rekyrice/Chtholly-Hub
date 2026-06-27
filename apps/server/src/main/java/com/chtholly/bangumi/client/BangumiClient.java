package com.chtholly.bangumi.client;

import com.chtholly.bangumi.config.BangumiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Bangumi v0 API 客户端（Redisson 全局限流）。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bangumi.enabled", havingValue = "true", matchIfMissing = true)
public class BangumiClient {

    private static final String RATE_LIMITER_KEY = "bangumi:api:global";

    private final BangumiProperties properties;
    private final RestTemplate bangumiRestTemplate;
    private final RedissonClient redisson;

    /** 获取每日放送表。 */
    public JsonNode fetchCalendar() {
        return exchangeJson(properties.getBaseUrl() + "/calendar", HttpMethod.GET, null);
    }

    /** 搜索条目。 */
    public JsonNode searchSubjects(String keyword, int limit) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.getBaseUrl() + "/v0/search/subjects")
                .queryParam("limit", Math.min(Math.max(limit, 1), 25))
                .queryParam("offset", 0)
                .toUriString();

        Map<String, Object> body = Map.of("keyword", keyword, "sort", "match");
        return exchangeJson(url, HttpMethod.POST, body);
    }

    /** 获取条目详情。 */
    public JsonNode getSubject(long subjectId) {
        String url = properties.getBaseUrl() + "/v0/subjects/" + subjectId;
        return exchangeJson(url, HttpMethod.GET, null);
    }

    /** 获取分集列表（含 total）。 */
    public JsonNode listEpisodes(long subjectId, int limit) {
        String url = UriComponentsBuilder
                .fromHttpUrl(properties.getBaseUrl() + "/v0/episodes")
                .queryParam("subject_id", subjectId)
                .queryParam("limit", Math.min(limit, 50))
                .queryParam("offset", 0)
                .toUriString();
        return exchangeJson(url, HttpMethod.GET, null);
    }

    private JsonNode exchangeJson(String url, HttpMethod method, Object body) {
        acquirePermit();
        HttpHeaders headers = buildHeaders(body != null);
        HttpEntity<?> entity = body == null
                ? new HttpEntity<>(headers)
                : new HttpEntity<>(body, headers);
        try {
            ResponseEntity<JsonNode> resp = bangumiRestTemplate.exchange(url, method, entity, JsonNode.class);
            return resp.getBody();
        } catch (Exception e) {
            log.warn("Bangumi API 调用失败 {} {}: {}", method, url, e.getMessage());
            return null;
        }
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
        int rate = Math.max(1, properties.getRatePerSecond());
        RRateLimiter limiter = redisson.getRateLimiter(RATE_LIMITER_KEY);
        limiter.trySetRate(RateType.OVERALL, rate, Duration.ofSeconds(1));
        limiter.acquire(1);
    }
}
