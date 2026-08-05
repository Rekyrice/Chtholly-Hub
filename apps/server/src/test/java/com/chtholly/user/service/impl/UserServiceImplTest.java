package com.chtholly.user.service.impl;

import com.chtholly.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserMapper userMapper;

    @Test
    void passwordRecoveryUsesOneCombinedMapperCommand() {
        when(userMapper.updatePasswordAndAdvanceRefreshSessionEpoch(
                7L, "encoded")).thenReturn(1);
        UserServiceImpl service = new UserServiceImpl(userMapper);

        service.updatePasswordAndAdvanceRefreshSessionEpoch(7L, "encoded");

        verify(userMapper).updatePasswordAndAdvanceRefreshSessionEpoch(
                7L, "encoded");
    }

    @Test
    void passwordRecoveryFailsWhenNoUserWasUpdated() {
        when(userMapper.updatePasswordAndAdvanceRefreshSessionEpoch(
                7L, "encoded")).thenReturn(0);
        UserServiceImpl service = new UserServiceImpl(userMapper);

        assertThatThrownBy(() ->
                service.updatePasswordAndAdvanceRefreshSessionEpoch(
                        7L, "encoded"))
                .isInstanceOf(IllegalStateException.class);
    }
}
