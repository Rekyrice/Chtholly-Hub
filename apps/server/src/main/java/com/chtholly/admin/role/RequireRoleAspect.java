package com.chtholly.admin.role;

import com.chtholly.auth.token.JwtService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 校验当前 JWT 用户是否具备 {@link RequireRole} 标注的角色。
 */
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
public class RequireRoleAspect {

    private final JwtService jwtService;
    private final UserMapper userMapper;

    @Around("@within(com.chtholly.admin.role.RequireRole) || @annotation(com.chtholly.admin.role.RequireRole)")
    public Object checkRole(ProceedingJoinPoint joinPoint) throws Throwable {
        RequireRole required = resolveRequireRole(joinPoint);
        if (required == null) {
            return joinPoint.proceed();
        }
        Jwt jwt = currentJwt();
        long userId = jwtService.extractUserId(jwt);
        User user = userMapper.findById(userId);
        if (user == null) {
            throw forbidden();
        }
        Role actual = Role.fromString(user.getRole());
        if (actual != required.value()) {
            throw forbidden();
        }
        return joinPoint.proceed();
    }

    private RequireRole resolveRequireRole(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequireRole methodRole = signature.getMethod().getAnnotation(RequireRole.class);
        if (methodRole != null) {
            return methodRole;
        }
        return joinPoint.getTarget().getClass().getAnnotation(RequireRole.class);
    }

    private Jwt currentJwt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw forbidden();
        }
        return jwt;
    }

    private BusinessException forbidden() {
        return new BusinessException(ErrorCode.FORBIDDEN, "权限不足", HttpStatus.FORBIDDEN.value());
    }
}
