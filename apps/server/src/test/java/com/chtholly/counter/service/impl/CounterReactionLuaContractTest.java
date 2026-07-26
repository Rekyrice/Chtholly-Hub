package com.chtholly.counter.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CounterReactionLuaContractTest {

    @Test
    void onlineReadAndRebuildFinalizationUseConstantSizeMetadataChecks() throws IOException {
        String read = source("lua/counter/read-reaction-state.lua");
        String finalize = source("lua/counter/finalize-reaction-rebuild.lua");

        assertThat(read)
                .doesNotContain("SMEMBERS")
                .contains("SCARD");
        assertThat(finalize)
                .doesNotContain("SMEMBERS")
                .doesNotContain("BITCOUNT")
                .contains("SCARD");
    }

    @Test
    void onlineProjectionCanRemoveButNeverPublishCompleteness() throws IOException {
        String project = source("lua/counter/project-reaction-state.lua");
        String finalize = source("lua/counter/finalize-reaction-rebuild.lua");
        String publish = source("lua/counter/publish-reaction-rebuild.lua");

        assertThat(project)
                .contains("completeKey")
                .contains("redis.call('DEL', completeKey)")
                .doesNotContain("redis.call('SET', completeKey");
        assertThat(finalize)
                .doesNotContain("redis.call('SET', completeKey")
                .doesNotContain("redis.call('DEL', fenceKey)")
                .contains("redis.call('SET', fenceKey, '@prepared:' .. token)");
        assertThat(publish)
                .contains("redis.call('GET', fenceKey) ~= '@prepared:' .. token")
                .contains("redis.call('SET', completeKey, completeVersion)")
                .contains("redis.call('DEL', fenceKey)");
    }

    @Test
    void completenessChecksRedisTypeBeforeReadingTheMarker() throws IOException {
        String complete = source("lua/counter/is-reaction-projection-complete.lua");

        assertThat(complete)
                .contains("redis.call('TYPE', completeKey)")
                .contains("if completeType ~= 'string' then return 0 end")
                .contains("redis.call('GET', completeKey)");
        assertThat(complete.indexOf("redis.call('TYPE', completeKey)"))
                .isLessThan(complete.indexOf("redis.call('GET', completeKey)"));
    }

    @Test
    void maintenanceEventDirtiesOwnershipInsteadOfPublishingAStaleRebuild()
            throws IOException {
        String project = source("lua/counter/project-reaction-state.lua");
        String publish = source("lua/counter/publish-reaction-rebuild.lua");
        String abort = source("lua/counter/abort-reaction-rebuild.lua");

        assertThat(project)
                .contains("string.sub(fenceValue, 1, 10) == '@prepared:'")
                .contains("redis.call('SET', fenceKey, '@dirty:' .. fenceValue)")
                .contains("redis.call('DEL', completeKey)")
                .contains("return {-1, 0}");
        assertThat(publish)
                .contains("redis.call('GET', fenceKey) ~= '@prepared:' .. token");
        assertThat(abort)
                .contains("fenceValue ~= token")
                .contains("fenceValue ~= '@prepared:' .. token")
                .contains("fenceValue ~= '@dirty:' .. token");
    }

    private static String source(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
