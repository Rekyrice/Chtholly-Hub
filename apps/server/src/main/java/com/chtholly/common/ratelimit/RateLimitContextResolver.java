package com.chtholly.common.ratelimit;

import com.chtholly.auth.token.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Resolves rate-limit dimensions (IP, userId, body fields) from the current request context. */
@Component
@RequiredArgsConstructor
public class RateLimitContextResolver {

    private final JwtService jwtService;

    public String resolveIdentifier(RateLimit limit, JoinPoint joinPoint) {
        return switch (limit.dimension()) {
            case IP -> resolveClientIp();
            case USER -> String.valueOf(resolveUserId(joinPoint));
            case IDENTIFIER -> resolveBodyField(joinPoint, limit.identifierParam());
        };
    }

    public List<RateLimit> resolveLimits(JoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        List<RateLimit> limits = new ArrayList<>();
        RateLimit single = method.getAnnotation(RateLimit.class);
        if (single != null) {
            limits.add(single);
        }
        RateLimits container = method.getAnnotation(RateLimits.class);
        if (container != null) {
            for (RateLimit limit : container.value()) {
                limits.add(limit);
            }
        }
        return limits;
    }

    private long resolveUserId(JoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof Jwt jwt) {
                return jwtService.extractUserId(jwt);
            }
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwtService.extractUserId(jwt);
        }
        throw new IllegalStateException("USER rate limit requires authenticated JWT");
    }

    private String resolveBodyField(JoinPoint joinPoint, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalStateException("IDENTIFIER rate limit requires identifierParam");
        }
        for (Object arg : joinPoint.getArgs()) {
            if (arg == null) {
                continue;
            }
            String value = readProperty(arg, fieldName);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "unknown";
    }

    private String readProperty(Object target, String fieldName) {
        try {
            Method accessor = target.getClass().getMethod(fieldName);
            Object value = accessor.invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private String resolveClientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs == null ? null : attrs.getRequest();
    }
}
