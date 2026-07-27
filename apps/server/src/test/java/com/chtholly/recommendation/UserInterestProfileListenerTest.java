package com.chtholly.recommendation;

import com.chtholly.counter.event.CounterEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserInterestProfileListenerTest {

    @Mock
    private UserInterestProfile userInterestProfile;

    private UserInterestProfileListener listener;

    @BeforeEach
    void setUp() {
        listener = new UserInterestProfileListener(userInterestProfile);
    }

    @Test
    void positivePostLikeUpdatesTheActorProfile() {
        CounterEvent event = CounterEvent.of("301", "post", "42", "like", 1, 9L, 1);

        listener.onCounterEvent(event);

        verify(userInterestProfile).updateProfile(9L, 42L, "like");
    }

    @Test
    void removalNonPostAndInvalidPostIdDoNotUpdateTheProfile() {
        listener.onCounterEvent(CounterEvent.of("302", "post", "42", "like", 1, 9L, -1));
        listener.onCounterEvent(CounterEvent.of("303", "comment", "42", "like", 1, 9L, 1));
        listener.onCounterEvent(CounterEvent.of("304", "post", "not-a-number", "fav", 2, 9L, 1));

        verifyNoInteractions(userInterestProfile);
    }
}
