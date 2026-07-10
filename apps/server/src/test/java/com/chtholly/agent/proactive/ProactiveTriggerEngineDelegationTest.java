package com.chtholly.agent.proactive;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProactiveTriggerEngineDelegationTest {

    @Test
    void scheduledEntrypointsDelegateToTheirDomainServices() {
        EmotionalProactiveService emotional = mock(EmotionalProactiveService.class);
        ContentProactiveService content = mock(ContentProactiveService.class);
        SocialProactiveService social = mock(SocialProactiveService.class);
        ProactiveTriggerEngine engine = new ProactiveTriggerEngine(emotional, content, social);

        engine.checkTriggers();
        engine.sendDailyHotDigest();
        engine.pushWeeklyCuration();
        engine.detectRisingStars();
        engine.detectInterestMatches();
        engine.introduceNewResidents();

        verify(emotional).checkTriggers();
        verify(content).checkUnreadPosts();
        verify(content).sendDailyHotDigest();
        verify(content).pushWeeklyCuration();
        verify(content).detectRisingStars();
        verify(social).detectInterestMatches();
        verify(social).introduceNewResidents();
    }
}
