package com.chtholly.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolParamValidatorTest {

    private static final Map<String, ParamDef> SCHEMA = Map.of(
            "keyword", new ParamDef("条目名", String.class, true),
            "topK", new ParamDef("返回条数", Integer.class, false)
    );

    @Test
    void passesWhenRequiredPresent() {
        Optional<String> err = AgentToolParamValidator.validate(
                Map.of("keyword", "re0", "topK", 5),
                SCHEMA);
        assertThat(err).isEmpty();
    }

    @Test
    void missingRequiredReturnsObservationMessage() {
        Optional<String> err = AgentToolParamValidator.validate(Map.of("topK", 3), SCHEMA);
        assertThat(err).isPresent();
        assertThat(err.get()).isEqualTo("Missing required parameter: keyword");
    }

    @Test
    void blankRequiredTreatedAsMissing() {
        Optional<String> err = AgentToolParamValidator.validate(Map.of("keyword", "  "), SCHEMA);
        assertThat(err).isPresent();
        assertThat(err.get()).isEqualTo("Missing required parameter: keyword");
    }

    @Test
    void invalidTypeReturnsMessage() {
        Optional<String> err = AgentToolParamValidator.validate(
                Map.of("keyword", "re0", "topK", "not-a-number"),
                SCHEMA);
        assertThat(err).isPresent();
        assertThat(err.get()).isEqualTo("Invalid type for parameter: topK");
    }

    @Test
    void enforcesStringLengthBounds() {
        Map<String, ParamDef> schema = Map.of(
                "query", ParamDef.string("查询文本", true, 2, 5));

        assertThat(AgentToolParamValidator.validate(Map.of("query", "a"), schema))
                .contains("Parameter query must contain at least 2 characters");
        assertThat(AgentToolParamValidator.validate(Map.of("query", "abcdef"), schema))
                .contains("Parameter query must contain at most 5 characters");
        assertThat(AgentToolParamValidator.validate(Map.of("query", "  迷宫饭  "), schema))
                .isEmpty();
    }

    @Test
    void enforcesIntegerBoundsAndRejectsFractionalNumbers() {
        Map<String, ParamDef> schema = Map.of(
                "topK", ParamDef.integer("返回条数", false, 1, 10));

        assertThat(AgentToolParamValidator.validate(Map.of("topK", 0), schema))
                .contains("Parameter topK must be at least 1");
        assertThat(AgentToolParamValidator.validate(Map.of("topK", 11), schema))
                .contains("Parameter topK must be at most 10");
        assertThat(AgentToolParamValidator.validate(Map.of("topK", 1.5), schema))
                .contains("Invalid type for parameter: topK");
    }

    @Test
    void enforcesDeclaredEnumValues() {
        Map<String, ParamDef> schema = Map.of(
                "work_type", ParamDef.enumString(
                        "作品类型", false, List.of("book", "anime", "all")));

        assertThat(AgentToolParamValidator.validate(Map.of("work_type", "game"), schema))
                .contains("Parameter work_type must be one of: book, anime, all");
        assertThat(AgentToolParamValidator.validate(Map.of("work_type", " anime "), schema))
                .isEmpty();
    }

    @Test
    void emptySchemaSkipsValidation() {
        Optional<String> err = AgentToolParamValidator.validate(Map.of(), Map.of());
        assertThat(err).isEmpty();
    }

    @Test
    void toolExecutionTimeoutPattern() {
        CompletableFuture<String> pending = new CompletableFuture<>();

        assertThatThrownBy(() -> pending.get(20, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }
}
