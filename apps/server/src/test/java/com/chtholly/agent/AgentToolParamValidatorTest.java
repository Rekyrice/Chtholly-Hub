package com.chtholly.agent;

import org.junit.jupiter.api.Test;

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
