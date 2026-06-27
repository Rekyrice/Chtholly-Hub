package com.chtholly.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentJsonExtractorTest {

    private AgentJsonExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new AgentJsonExtractor(new ObjectMapper());
    }

    @Test
    void extractsPureJson() {
        String json = extractor.extractActionJson("{\"action\":\"bangumi_search\",\"input\":{\"keyword\":\"re0\"}}");
        assertThat(json).contains("\"action\":\"bangumi_search\"");
    }

    @Test
    void extractsJsonWithPrefixAndSuffix() {
        String text = "思考过程... {\"action\":\"bangumi_search\",\"input\":{\"keyword\":\"re0\"}} 后面的文字";
        String json = extractor.extractActionJson(text);
        assertThat(json).isEqualTo("{\"action\":\"bangumi_search\",\"input\":{\"keyword\":\"re0\"}}");
    }

    @Test
    void avoidsGreedyMatchWithTrailingBraceInText() {
        String text = "分析：{\"action\":\"final\",\"answer\":\"占位\"} 后面还有 } 字符";
        String json = extractor.extractActionJson(text);
        assertThat(json).isEqualTo("{\"action\":\"final\",\"answer\":\"占位\"}");
    }

    @Test
    void picksLastValidJsonWhenMultipleBlocks() {
        String text = """
                先试：{"action":"fulltext_search","input":{"query":"x"}}
                修正：{"action":"bangumi_search","input":{"keyword":"re0"}}
                """;
        String json = extractor.extractActionJson(text);
        assertThat(json).contains("bangumi_search");
    }

    @Test
    void rejectsJsonWithoutActionField() {
        assertThatThrownBy(() -> extractor.extractActionJson("{\"input\":{\"q\":\"x\"}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPlainText() {
        assertThatThrownBy(() -> extractor.extractActionJson("这不是 JSON"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
