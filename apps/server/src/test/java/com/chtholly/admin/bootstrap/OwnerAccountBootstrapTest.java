package com.chtholly.admin.bootstrap;

import com.chtholly.config.SiteProperties;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.chtholly.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnerAccountBootstrapTest {

    @Mock private UserMapper userMapper;
    @Mock private UserService userService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationArguments arguments;

    @Test
    void initialPasswordAdvancesRefreshSessionEpochThroughUserService() {
        SiteProperties properties = new SiteProperties(
                7L, 888L, " raw-password ", "owner", "Owner");
        User existing = User.builder().id(7L).passwordHash(null).build();
        when(userMapper.findById(7L)).thenReturn(existing);
        when(passwordEncoder.encode("raw-password")).thenReturn("encoded");
        OwnerAccountBootstrap bootstrap = new OwnerAccountBootstrap(
                properties, userMapper, passwordEncoder, userService);

        bootstrap.run(arguments);

        verify(userService).updatePasswordAndAdvanceRefreshSessionEpoch(
                7L, "encoded");
        verify(userMapper).updateProfile(argThat(profile ->
                profile.getId().equals(7L)
                        && "owner".equals(profile.getHandle())
                        && "Owner".equals(profile.getNickname())));
        verify(userMapper).updateRole(7L, "ADMIN");
    }

    @Test
    void existingPasswordDoesNotAdvanceEpochOnEveryRestart() {
        SiteProperties properties = new SiteProperties(
                7L, 888L, "raw-password", "owner", "Owner");
        User existing = User.builder()
                .id(7L)
                .passwordHash("already-configured")
                .build();
        when(userMapper.findById(7L)).thenReturn(existing);
        OwnerAccountBootstrap bootstrap = new OwnerAccountBootstrap(
                properties, userMapper, passwordEncoder, userService);

        bootstrap.run(arguments);

        verify(userService, never())
                .updatePasswordAndAdvanceRefreshSessionEpoch(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());
        verify(passwordEncoder, never()).encode(
                org.mockito.ArgumentMatchers.anyString());
    }
}
