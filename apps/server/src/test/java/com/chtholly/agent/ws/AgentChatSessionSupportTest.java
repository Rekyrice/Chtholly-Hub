package com.chtholly.agent.ws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentChatSessionSupportTest {

    @Test
    void acceptsFrontendSessionIds() {
        assertThat(AgentChatSessionSupport.isValid("sess-1782663886970-hj44ya")).isTrue();
    }

    @Test
    void rejectsBlankOrInvalid() {
        assertThat(AgentChatSessionSupport.isValid("")).isFalse();
        assertThat(AgentChatSessionSupport.isValid("bad id")).isFalse();
        assertThat(AgentChatSessionSupport.isValid("../escape")).isFalse();
    }
}
