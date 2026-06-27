package com.chtholly.admin.role;

import com.chtholly.auth.token.JwtService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequireRoleAspectTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ProceedingJoinPoint joinPoint;

    private RequireRoleAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new RequireRoleAspect(jwtService, userMapper);
        SecurityContextHolder.clearContext();
    }

    @Test
    void given_nonAdmin_when_checkRole_then_forbidden() throws Throwable {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("uid", 2L)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        when(jwtService.extractUserId(jwt)).thenReturn(2L);
        when(userMapper.findById(2L)).thenReturn(User.builder().id(2L).role("USER").build());

        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(Object.class.getMethod("toString"));
        when(joinPoint.getTarget()).thenReturn(new AnnotatedTarget());

        assertThatThrownBy(() -> aspect.checkRole(joinPoint))
                .isInstanceOf(BusinessException.class);
    }

    @RequireRole(Role.ADMIN)
    private static final class AnnotatedTarget {
    }
}
