package com.chtholly.recommendation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * JSON 标签数组解析工具。
 */
@Component
@Slf4j
public class TagJsonParser {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public TagJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> tags = objectMapper.readValue(json, STRING_LIST);
            return tags == null ? List.of() : tags.stream().filter(t -> t != null && !t.isBlank()).toList();
        } catch (Exception e) {
            log.debug("标签 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }
}
