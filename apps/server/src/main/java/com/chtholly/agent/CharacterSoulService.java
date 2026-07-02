package com.chtholly.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads Chtholly's character soul prompt from classpath resources.
 *
 * <p>The soul file is intentionally externalized so persona changes do not
 * require touching the ReAct engine logic.
 */
@Slf4j
@Service
public class CharacterSoulService {

    private final String soulContent;

    /**
     * Loads the character soul markdown during application startup.
     *
     * @param soulResource Classpath resource containing the character soul.
     * @throws IOException if the resource cannot be read.
     */
    public CharacterSoulService(
            @Value("classpath:agent/character-soul.md") Resource soulResource
    ) throws IOException {
        this(new String(soulResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        log.info("Character soul loaded from classpath:agent/character-soul.md, chars={}", soulContent.length());
    }

    CharacterSoulService(String soulContent) {
        this.soulContent = soulContent == null ? "" : soulContent.trim();
    }

    /**
     * Returns the immutable character soul markdown.
     *
     * @return Character soul content.
     */
    public String getSoulContent() {
        return soulContent;
    }
}
