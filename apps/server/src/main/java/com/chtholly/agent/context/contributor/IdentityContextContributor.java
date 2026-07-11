package com.chtholly.agent.context.contributor;

import com.chtholly.agent.context.ContextContribution;
import com.chtholly.agent.context.ContextContributor;
import com.chtholly.agent.context.ContextOrder;
import com.chtholly.agent.context.ContextRequest;
import org.springframework.stereotype.Component;

/** Renders the character identity anchor. */
@Component
public class IdentityContextContributor implements ContextContributor {

    @Override
    public String name() {
        return "identity";
    }

    @Override
    public int order() {
        return ContextOrder.IDENTITY;
    }

    @Override
    public ContextContribution contribute(ContextRequest request) {
        String soul = request.anchors().soul();
        return new ContextContribution(name(), order(),
                "## 你的身份\n\n" + (soul == null ? "" : soul.trim()), false);
    }
}
