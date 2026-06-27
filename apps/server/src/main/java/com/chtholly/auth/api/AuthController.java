package com.chtholly.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.chtholly.common.ratelimit.RateLimit;
import com.chtholly.common.ratelimit.RateLimitDimension;
import com.chtholly.common.ratelimit.RateLimits;
import com.chtholly.common.web.HttpCacheHelper;
import com.chtholly.auth.api.dto.AuthResponse;
import com.chtholly.auth.api.dto.AuthUserResponse;
import com.chtholly.auth.api.dto.LoginRequest;
import com.chtholly.auth.api.dto.LogoutRequest;
import com.chtholly.auth.api.dto.PasswordResetRequest;
import com.chtholly.auth.api.dto.RegisterRequest;
import com.chtholly.auth.api.dto.SendCodeRequest;
import com.chtholly.auth.api.dto.SendCodeResponse;
import com.chtholly.auth.api.dto.TokenRefreshRequest;
import com.chtholly.auth.api.dto.TokenResponse;
import com.chtholly.auth.model.ClientInfo;
import com.chtholly.auth.service.AuthService;
import com.chtholly.auth.token.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication REST API: verification codes, registration, login, token lifecycle, and current user.
 *
 * <p>Uses Spring Security resource-server JWT injection; client IP and User-Agent are captured for audit logs.</p>
 */
@Tag(name = "认证", description = "登录、注册、Token 管理")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    /**
     * Sends a one-time verification code for register, login, or password reset.
     *
     * @param request identifier type, address, and scene
     * @return target identifier, scene, and code expiry seconds
     */
    @Operation(summary = "发送验证码")
    @RateLimits({
            @RateLimit(key = "auth:send-code", maxRequests = 5, windowSeconds = 60, dimension = RateLimitDimension.IP),
            @RateLimit(key = "auth:send-code", maxRequests = 30, windowSeconds = 3600, dimension = RateLimitDimension.IP)
    })
    @PostMapping("/send-code")
    public SendCodeResponse sendCode(@Valid @RequestBody SendCodeRequest request) {
        return authService.sendCode(request);
    }

    /**
     * Registers a new user and returns an access/refresh token pair.
     *
     * @param request registration fields including optional password
     * @param httpRequest HTTP request used to resolve client IP and User-Agent
     * @return authenticated user profile and tokens
     */
    @Operation(summary = "注册并登录")
    @RateLimit(key = "auth:register", maxRequests = 5, windowSeconds = 3600, dimension = RateLimitDimension.IP)
    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        return authService.register(request, resolveClient(httpRequest));
    }

    /**
     * Logs in with password or verification code and returns tokens.
     *
     * @param request login credentials
     * @param httpRequest HTTP request used to resolve client IP and User-Agent
     * @return authenticated user profile and tokens
     */
    @Operation(summary = "登录")
    @RateLimits({
            @RateLimit(key = "auth:login:ip", maxRequests = 20, windowSeconds = 60, dimension = RateLimitDimension.IP),
            @RateLimit(key = "auth:login:id", maxRequests = 10, windowSeconds = 60,
                    dimension = RateLimitDimension.IDENTIFIER, identifierParam = "identifier")
    })
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, resolveClient(httpRequest));
    }

    /**
     * Rotates access and refresh tokens using a valid refresh token.
     *
     * @param request refresh token payload
     * @return new access and refresh tokens with expiry metadata
     */
    @Operation(summary = "刷新 Token")
    @PostMapping("/token/refresh")
    public TokenResponse refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return authService.refresh(request);
    }

    /**
     * Revokes the provided refresh token and ends the session.
     *
     * @param request refresh token to revoke
     * @return HTTP 204 with no body
     */
    @Operation(summary = "登出")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Resets the account password after verification-code validation.
     *
     * @param request identifier, verification code, and new password
     * @return HTTP 204 with no body
     */
    @Operation(summary = "重置密码")
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the authenticated user's public profile summary.
     *
     * @param jwt current access token JWT
     * @return current user profile
     */
    @Operation(summary = "当前登录用户信息")
    @GetMapping("/me")
    public ResponseEntity<AuthUserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        AuthUserResponse body = authService.me(userId);
        return HttpCacheHelper.okPrivate(body);
    }

    /**
     * Resolves client audit metadata from the HTTP request.
     *
     * @param request HTTP servlet request
     * @return client IP and User-Agent
     */
    private ClientInfo resolveClient(HttpServletRequest request) {
        String ip = extractClientIp(request);
        String ua = request.getHeader("User-Agent");
        return new ClientInfo(ip, ua);
    }

    /**
     * Extracts the client IP, preferring {@code X-Forwarded-For} and {@code X-Real-IP} headers.
     *
     * @param request HTTP servlet request
     * @return resolved client IP address
     */
    private String extractClientIp(HttpServletRequest request) {
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
}
