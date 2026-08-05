package com.chtholly.auth.token;

import com.chtholly.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshSessionEpochAuthorityTest {

    @Mock private UserMapper userMapper;

    @Test
    void currentFailsClosedForMissingAndNonPositiveEpochs() {
        RefreshSessionEpochAuthority authority =
                new RefreshSessionEpochAuthority(userMapper);
        when(userMapper.findRefreshSessionEpoch(7L)).thenReturn(null);
        when(userMapper.findRefreshSessionEpoch(8L)).thenReturn(0L);

        assertThatThrownBy(() -> authority.current(7L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> authority.current(8L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void advanceRequiresExactlyOneAffectedRow() {
        RefreshSessionEpochAuthority authority =
                new RefreshSessionEpochAuthority(userMapper);
        when(userMapper.advanceRefreshSessionEpoch(7L)).thenReturn(1);
        when(userMapper.advanceRefreshSessionEpoch(8L)).thenReturn(0);

        authority.advance(7L);
        assertThatThrownBy(() -> authority.advance(8L))
                .isInstanceOf(IllegalStateException.class);

        verify(userMapper).advanceRefreshSessionEpoch(7L);
    }

    @Test
    void pendingUserChecksRequireAnInitialEpochVisibleOnlyToTheOuterTransaction() {
        RefreshSessionEpochAuthority authority =
                new RefreshSessionEpochAuthority(userMapper);
        when(userMapper.findRefreshSessionEpoch(7L)).thenReturn(1L);
        when(userMapper.findRefreshSessionEpoch(8L)).thenReturn(2L);
        when(userMapper.findRefreshSessionEpoch(9L)).thenReturn(null);

        assertThat(authority.hasInitialEpochInCurrentTransaction(7L)).isTrue();
        assertThat(authority.hasInitialEpochInCurrentTransaction(8L)).isFalse();
        assertThat(authority.existsInCommittedSnapshot(7L)).isTrue();
        assertThat(authority.existsInCommittedSnapshot(9L)).isFalse();
    }

    @Test
    void transactionAnnotationsProvideFreshReadsAndRequiredAdvances()
            throws Exception {
        Method current = RefreshSessionEpochAuthority.class
                .getMethod("current", long.class);
        Method advance = RefreshSessionEpochAuthority.class
                .getMethod("advance", long.class);
        Method initialEpoch = RefreshSessionEpochAuthority.class
                .getMethod("hasInitialEpochInCurrentTransaction", long.class);
        Method committedUser = RefreshSessionEpochAuthority.class
                .getMethod("existsInCommittedSnapshot", long.class);

        assertThat(current.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(current.getAnnotation(Transactional.class).readOnly())
                .isTrue();
        assertThat(advance.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRED);
        assertThat(initialEpoch.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
        assertThat(initialEpoch.getAnnotation(Transactional.class).readOnly())
                .isTrue();
        assertThat(committedUser.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(committedUser.getAnnotation(Transactional.class).readOnly())
                .isTrue();
    }
}
