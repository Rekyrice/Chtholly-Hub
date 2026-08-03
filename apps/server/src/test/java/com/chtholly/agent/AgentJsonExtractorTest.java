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
    void extractsJsonFromMarkdownCodeBlock() {
        String text = """
                我来查一下。
                ```json
                {"action":"bangumi_search","input":{"keyword":"re0"}}
                ```
                """;
        String json = extractor.extractActionJson(text);
        assertThat(json).contains("bangumi_search");
    }

    @Test
    void repairsLiteralLineBreaksInsideFinalAnswer() throws Exception {
        String text = """
                {"action":"final","answer":"《迷宫饭》动画版评分 7.80。

                主要角色：
                - 莱欧斯
                - 玛露希尔"}
                """;

        String json = extractor.extractActionJson(text);

        assertThat(new ObjectMapper().readTree(json).path("answer").asText())
                .isEqualTo("《迷宫饭》动画版评分 7.80。\n\n主要角色：\n- 莱欧斯\n- 玛露希尔");
    }

    @Test
    void preservesAlreadyEscapedJsonStringContent() throws Exception {
        String text = "{\"action\":\"final\",\"answer\":\"第一行\\n\\t\\\"引号\\\"和\\\\路径\"}";

        String json = extractor.extractActionJson(text);

        assertThat(new ObjectMapper().readTree(json).path("answer").asText())
                .isEqualTo("第一行\n\t\"引号\"和\\路径");
    }

    @Test
    void repairsAllLiteralControlCharactersInsideJsonStrings() throws Exception {
        String expected = "A\r\nB\tC" + (char) 1 + "D";
        String text = "{\"action\":\"final\",\"answer\":\"" + expected + "\"}";

        String json = extractor.extractActionJson(text);

        assertThat(new ObjectMapper().readTree(json).path("answer").asText()).isEqualTo(expected);
    }

    @Test
    void stillRejectsStructurallyInvalidJson() {
        assertThatThrownBy(() -> extractor.extractActionJson(
                "{\"action\":\"final\",\"answer\":\"未闭合"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> extractor.extractActionJson(
                "{\"action\":\"final\",\"answer\":\"非法\\x转义\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractsMultilineJsonBlockAfterQuotedProse() throws Exception {
        String text = """
                前文里有一个单独的 " 引号。
                ```json
                {"action":"final","answer":"第一行
                第二行"}
                ```
                """;

        String json = extractor.extractActionJson(text);

        assertThat(new ObjectMapper().readTree(json).path("answer").asText())
                .isEqualTo("第一行\n第二行");
    }

    @Test
    void quotedProseDoesNotCorruptFollowingMultilineActionObject() throws Exception {
        String text = "前文里有一个单独的 \" 引号，然后才是结果："
                + "{\"action\":\"final\",\"answer\":\"第一行\n第二行\"}";

        String json = extractor.extractActionJson(text);

        assertThat(new ObjectMapper().readTree(json).path("answer").asText())
                .isEqualTo("第一行\n第二行");
    }

    @Test
    void rejectsPlainText() {
        assertThatThrownBy(() -> extractor.extractActionJson("这不是 JSON"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
