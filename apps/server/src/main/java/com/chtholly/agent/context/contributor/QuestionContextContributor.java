package com.chtholly.agent.context.contributor;

import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextContributor;
import com.chtholly.agent.context.ContextOrder;
import com.chtholly.agent.context.ContextRequest;
import org.springframework.stereotype.Component;

/** Renders the current user question. */
@Component
public class QuestionContextContributor implements ContextContributor {

    @Override
    public String name() {
        return "question";
    }

    @Override
    public int order() {
        return ContextOrder.QUESTION;
    }

    @Override
    public ContextContribution contribute(ContextRequest request) {
        String question = request.userQuestion();
        return new ContextContribution(name(), order(),
                "## 用户的问题\n\n" + (question == null ? "" : question.trim()), false);
    }
}
