package com.chtholly.agent.proactive;

import com.chtholly.agent.state.CharacterStateService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Resolves the active audience shared by proactive domains. */
@Service
class ProactiveAudienceService {
    private final CharacterStateService characterStateService;
    private final CharacterStateUserActivityProvider activityProvider;

    @Autowired
    ProactiveAudienceService(
            CharacterStateService characterStateService,
            ObjectProvider<CharacterStateUserActivityProvider> activityProvider) {
        this(characterStateService, activityProvider.getIfAvailable());
    }

    ProactiveAudienceService(
            CharacterStateService characterStateService,
            CharacterStateUserActivityProvider activityProvider) {
        this.characterStateService = characterStateService;
        this.activityProvider = activityProvider;
    }

    List<Long> activeUserIds() {
        if (activityProvider != null) {
            List<Long> active = activityProvider.findActiveUserIds();
            if (active != null && !active.isEmpty()) {
                return active;
            }
        }
        return characterStateService.findUserIdsActiveSince(
                Instant.now().minus(ProactiveTriggerEngine.ACTIVE_USER_WINDOW));
    }
}
