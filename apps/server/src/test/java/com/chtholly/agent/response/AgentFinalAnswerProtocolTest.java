package com.chtholly.agent.response;

import com.chtholly.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentFinalAnswerProtocolTest {

    private final AgentProperties properties = new AgentProperties();
    private final AgentFinalAnswerProtocol protocol =
            new AgentFinalAnswerProtocol(new ObjectMapper(), properties);

    @Test
    void detectsPlainAndSingleFencedActionEnvelopes() {
        assertThat(protocol.isActionEnvelope("{\"action\":\"final\"}")).isTrue();
        assertThat(protocol.isActionEnvelope("```json\n{\"action\":\"final\"}\n```"))
                .isTrue();
        assertThat(protocol.isActionEnvelope("这是给用户的 Markdown 回答。")).isFalse();
    }

    @Test
    void acceptsCitationOnlyChangesButRejectsRewrittenContent() {
        assertThat(protocol.sameContentExceptCitations(
                "第一句。第二句。",
                "第一句。[E1] 第二句。[E2]"))
                .isTrue();
        assertThat(protocol.sameContentExceptCitations(
                "第一句。第二句。",
                "第一句被改写。[E1] 第二句。"))
                .isFalse();
    }

    @Test
    void truncatesAtTheConfiguredVisibleAnswerLimit() {
        properties.setMaxResponseChars(4);

        assertThat(protocol.truncate("123456")).isEqualTo("1234");
    }
}
