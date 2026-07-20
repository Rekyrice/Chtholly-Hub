package com.chtholly.agent.skill;

import java.util.Set;

/** Immutable inputs and permission bounds used for one Skill selection. */
public record SkillExecutionContext(
        long userId,
        String chatSessionId,
        String taskType,
        String question,
        String pageContext,
        Set<String> permittedToolNames,
        Set<String> enabledToolNames) {

    public SkillExecutionContext {
        chatSessionId = chatSessionId == null ? "" : chatSessionId;
        taskType = taskType == null ? "" : taskType.strip();
        question = question == null ? "" : question.strip();
        pageContext = pageContext == null ? "" : pageContext.strip();
        permittedToolNames = permittedToolNames == null ? Set.of() : Set.copyOf(permittedToolNames);
        enabledToolNames = enabledToolNames == null ? Set.of() : Set.copyOf(enabledToolNames);
    }
}
