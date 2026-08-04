package com.chtholly.post.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/** Encodes and decodes JSON fields stored on post rows. */
@Component
public class PostPayloadCodec {

    private static final Logger log = LoggerFactory.getLogger(PostPayloadCodec.class);

    private final ObjectMapper objectMapper;

    /**
     * Creates the post payload codec.
     *
     * @param objectMapper application JSON mapper
     */
    public PostPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String toJsonOrNull(List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException failure) {
            log.warn("Post metadata JSON serialization failed: {}", failure.getMessage(), failure);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JSON 处理失败");
        }
    }

    List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception failure) {
            log.debug("Stored post string array could not be parsed", failure);
            return Collections.emptyList();
        }
    }
}
