package com.chtholly.seed.contentpack.model;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

/**
 * Immutable author account input loaded from a content pack.
 */
public record SeedAccountDefinition(
        String seedKey,
        String legacyHandle,
        String nickname,
        String handle,
        String bio,
        String avatarAsset,
        String gender,
        LocalDate birthday,
        String school,
        List<String> tags,
        Instant joinedAt,
        AuthorVoice voice) {

    /**
     * Protects account tags from parser-owned list mutations.
     */
    public SeedAccountDefinition {
        tags = List.copyOf(tags);
    }

    /** Creates an account from legacy packs that did not declare a stable join time. */
    public SeedAccountDefinition(
            String seedKey,
            String legacyHandle,
            String nickname,
            String handle,
            String bio,
            String avatarAsset,
            String gender,
            LocalDate birthday,
            String school,
            List<String> tags,
            AuthorVoice voice) {
        this(seedKey, legacyHandle, nickname, handle, bio, avatarAsset, gender, birthday, school, tags, null, voice);
    }

    /**
     * Six approved dimensions used to describe an author's writing voice.
     */
    public record AuthorVoice(
            String sentenceLength,
            List<String> commonPhrases,
            List<String> interests,
            List<String> biases,
            List<String> knowledgeBoundaries,
            List<String> forbiddenExpressions) {

        /**
         * Protects voice dimensions from parser-owned list mutations.
         */
        public AuthorVoice {
            commonPhrases = List.copyOf(commonPhrases);
            interests = List.copyOf(interests);
            biases = List.copyOf(biases);
            knowledgeBoundaries = List.copyOf(knowledgeBoundaries);
            forbiddenExpressions = List.copyOf(forbiddenExpressions);
        }
    }
}
