package com.chtholly.agent.cognitive;

import com.chtholly.agent.experience.ArchivedExperience;
import com.chtholly.agent.experience.ArchivedExperienceMapper;
import com.chtholly.agent.experience.Experience;
import com.chtholly.agent.experience.WeeklyExperienceSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Stores Chtholly's recent experience stream in Redis.
 *
 * <p>The stream is intentionally small and append-only from the agent's view:
 * Redis List keeps the newest 50 observations for room surfaces such as /chtholly.
 */
@Slf4j
@Service
public class ExperienceService {

    private static final String KEY_PREFIX = "agent:experiences:";
    private static final String GLOBAL_KEY = KEY_PREFIX + "global";
    private static final String WEEKLY_KEY = KEY_PREFIX + "weekly";
    private static final int MAX_EXPERIENCES = 50;
    private static final int MEMORABLE_IMPORTANCE = 7;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ArchivedExperienceMapper archivedExperienceMapper;
    private final TextGenerator textGenerator;
    private final Clock clock;

    @Autowired
    public ExperienceService(StringRedisTemplate redis,
                             ObjectMapper objectMapper,
                             ArchivedExperienceMapper archivedExperienceMapper,
                             ObjectProvider<ChatClient> chatClientProvider) {
        this(redis,
                objectMapper,
                archivedExperienceMapper,
                prompt -> generateWithChatClient(chatClientProvider.getIfAvailable(), prompt),
                Clock.systemUTC());
    }

    ExperienceService(StringRedisTemplate redis,
                      ObjectMapper objectMapper,
                      ArchivedExperienceMapper archivedExperienceMapper,
                      TextGenerator textGenerator,
                      Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.archivedExperienceMapper = archivedExperienceMapper;
        this.textGenerator = textGenerator;
        this.clock = clock;
    }

    /**
     * Stores observations and trims the global stream to the latest 50 entries.
     *
     * @param experiences Observations to append.
     */
    public void storeExperiences(List<Observation> experiences) {
        if (experiences == null || experiences.isEmpty()) {
            return;
        }
        List<Experience> items = experiences.stream()
                .filter(observation -> observation != null)
                .map(this::fromObservation)
                .toList();
        storeAll(items);
    }

    /**
     * Stores a single event-driven experience.
     *
     * @param experience experience to append
     */
    public void store(Experience experience) {
        if (experience == null) {
            return;
        }
        storeAll(List.of(withCreatedAt(experience)));
    }

    private void storeAll(List<Experience> experiences) {
        if (experiences == null || experiences.isEmpty()) {
            return;
        }
        for (Experience experience : experiences) {
            if (experience == null || experience.text() == null || experience.text().isBlank()) {
                continue;
            }
            try {
                redis.opsForList().rightPush(GLOBAL_KEY, objectMapper.writeValueAsString(experience));
            } catch (Exception e) {
                log.warn("Store agent experience failed: {}", e.getMessage(), e);
            }
        }
        redis.opsForList().trim(GLOBAL_KEY, -MAX_EXPERIENCES, -1);
    }

    /**
     * Returns recent observations newest first.
     *
     * @param limit Maximum observations.
     * @return Recent observations.
     */
    public List<Observation> getRecentExperiences(int limit) {
        return getRecentExperienceItems(limit).stream()
                .map(this::toObservation)
                .toList();
    }

    /**
     * Returns recent raw experience items newest first.
     *
     * @param limit maximum items
     * @return recent experience items
     */
    public List<Experience> getRecentExperienceItems(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> payloads = redis.opsForList().range(GLOBAL_KEY, -limit, -1);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<Experience> experiences = new ArrayList<>();
        for (String payload : payloads) {
            Experience experience = parseExperience(payload);
            if (experience == null) {
                log.warn("Skip malformed agent experience payload");
                continue;
            }
            experiences.add(experience);
        }
        Collections.reverse(experiences);
        return List.copyOf(experiences);
    }

    /**
     * Weekly consolidation: merge old experiences into one Chtholly-style summary.
     */
    @Scheduled(cron = "0 0 3 * * MON")
    public void weeklyConsolidation() {
        Instant now = clock.instant();
        List<Experience> weekExperiences = getExperiencesBetween(now.minus(7, ChronoUnit.DAYS), now);
        if (weekExperiences.isEmpty()) {
            return;
        }
        String summary = textGenerator.generate("""
                将以下体验整合为 1-2 句话的周总结，用珂朵莉的语气：
                %s
                要求：保留重要事件，丢弃日常琐事，体现时间流逝感。
                """.formatted(formatExperiences(weekExperiences)));
        if (summary != null && !summary.isBlank()) {
            storeWeeklySummary(summary.trim(), getCurrentWeekKey());
        }
    }

    /**
     * Monthly archival: move old high-importance experiences to MySQL.
     */
    @Scheduled(cron = "0 0 3 1 * *")
    public void monthlyArchival() {
        List<Experience> old = getExperiencesBefore(clock.instant().minus(30, ChronoUnit.DAYS));
        if (old.isEmpty()) {
            return;
        }
        old.stream()
                .filter(experience -> experience.importance() >= MEMORABLE_IMPORTANCE)
                .forEach(archivedExperienceMapper::archive);
        redis.delete(GLOBAL_KEY);
    }

    /**
     * Returns weekly summaries newest first.
     *
     * @param limit max summaries
     * @return summaries
     */
    public List<WeeklyExperienceSummary> getWeeklySummaries(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<Object, Object> entries = redis.opsForHash().entries(WEEKLY_KEY);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.entrySet().stream()
                .map(entry -> new WeeklyExperienceSummary(String.valueOf(entry.getKey()), String.valueOf(entry.getValue())))
                .sorted(Comparator.comparing(WeeklyExperienceSummary::weekKey).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Returns archived memorable experiences.
     *
     * @param limit max archived rows
     * @return archived experiences
     */
    public List<ArchivedExperience> getArchivedMemories(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return archivedExperienceMapper.listRecentMemorable(Math.min(limit, 20));
    }

    private List<Experience> getExperiencesBetween(Instant start, Instant end) {
        return getAllExperienceItems().stream()
                .filter(experience -> !experience.createdAt().isBefore(start) && experience.createdAt().isBefore(end))
                .toList();
    }

    private List<Experience> getExperiencesBefore(Instant before) {
        return getAllExperienceItems().stream()
                .filter(experience -> experience.createdAt().isBefore(before))
                .toList();
    }

    private List<Experience> getAllExperienceItems() {
        List<String> payloads = redis.opsForList().range(GLOBAL_KEY, 0, -1);
        if (payloads == null || payloads.isEmpty()) {
            return List.of();
        }
        List<Experience> experiences = new ArrayList<>();
        for (String payload : payloads) {
            Experience experience = parseExperience(payload);
            if (experience != null) {
                experiences.add(experience);
            }
        }
        return experiences;
    }

    private void storeWeeklySummary(String summary, String weekKey) {
        redis.opsForHash().put(WEEKLY_KEY, weekKey, summary);
    }

    private String getCurrentWeekKey() {
        WeekFields weekFields = WeekFields.ISO;
        var date = java.time.LocalDate.now(clock);
        int week = date.get(weekFields.weekOfWeekBasedYear());
        int year = date.get(weekFields.weekBasedYear());
        return "%d-W%02d".formatted(year, week);
    }

    private Experience parseExperience(String payload) {
        try {
            return objectMapper.readValue(payload, Experience.class);
        } catch (Exception experienceParseFailure) {
            try {
                Observation observation = objectMapper.readValue(payload, Observation.class);
                return fromObservation(observation);
            } catch (Exception e) {
                log.warn("Parse agent experience payload failed: {}", experienceParseFailure.getMessage());
                return null;
            }
        }
    }

    private Experience fromObservation(Observation observation) {
        int importance = Math.clamp((int) Math.round(observation.valueScore() * 10), 1, 10);
        return new Experience(observation.text(), importance, observation.source(), observation.createdAt());
    }

    private Observation toObservation(Experience experience) {
        return new Observation(experience.text(), experience.importance() / 10.0, experience.createdAt(), experience.source());
    }

    private Experience withCreatedAt(Experience experience) {
        return experience.createdAt() == null
                ? new Experience(experience.text(), experience.importance(), experience.source(), clock.instant())
                : experience;
    }

    private static String formatExperiences(List<Experience> experiences) {
        return experiences.stream()
                .map(experience -> "- [%d] %s".formatted(experience.importance(), experience.text()))
                .toList()
                .toString();
    }

    private static String generateWithChatClient(ChatClient chatClient, String prompt) {
        if (chatClient == null) {
            return "";
        }
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    @FunctionalInterface
    public interface TextGenerator {
        String generate(String prompt);
    }
}
