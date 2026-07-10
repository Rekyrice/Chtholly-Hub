package com.chtholly.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chtholly.admin.security.BannedUserFilter;
import com.chtholly.common.web.ApiErrorBody;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 安全配置。
 * <p>
 * - 关闭 CSRF（后端纯 API，使用 JWT 无会话）；
 * - 启用 CORS 白名单，来源从 {@code cors.allowed-origins} 读取；
 * - 无状态会话；
 * - 公开认证相关接口与健康检查，其余接口需鉴权；
 * - 资源服务器启用 JWT 校验。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${cors.allowed-origins}")
    private String[] allowedOrigins;

    private final ObjectMapper objectMapper;
    private final BannedUserFilter bannedUserFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterAfter(bannedUserFilter, BearerTokenAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(),
                                    ApiErrorBody.of("FORBIDDEN", "权限不足"));
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/api-docs",
                                "/api-docs/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .requestMatchers("/api/v1/posts/feed").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/tags").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/search").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/search/hub-feed").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/search/suggest").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/recommendations").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/topics").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/topics/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/relation/following").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/relation/followers").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/relation/counter").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/users/*").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/posts/*/comments").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/posts/detail/*").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/posts/detail/by-slug/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/posts/*/related").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/posts/*/qa/stream").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/agent/experiences").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/agent/experiences/**").permitAll()
                        .requestMatchers("/api/v1/agent/ws").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/send-code",
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/token/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/password/reset"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
