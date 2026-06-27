package com.chtholly.counter.api;

import com.chtholly.counter.api.dto.ActionRequest;
import com.chtholly.counter.service.CounterService;
import com.chtholly.auth.token.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authenticated like and favorite toggle endpoints for arbitrary entities.
 */
@RestController
@RequestMapping("/api/v1/action")
public class ActionController {

    private final CounterService counterService;
    private final JwtService jwtService;

    public ActionController(CounterService counterService, JwtService jwtService) {
        this.counterService = counterService;
        this.jwtService = jwtService;
    }

    /**
     * Likes an entity for the authenticated user.
     *
     * @param req entity type and ID
     * @param jwt authenticated user JWT
     * @return map with {@code changed} flag and current {@code liked} state
     */
    @PostMapping("/like")
    public ResponseEntity<Map<String, Object>> like(@Valid @RequestBody ActionRequest req,
                                                    @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        boolean changed = counterService.like(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 标识这次操作是否改变状态（避免重复点击）
                "liked", counterService.isLiked(req.getEntityType(), req.getEntityId(), uid)
        ));
    }

    /**
     * Removes a like from an entity for the authenticated user.
     *
     * @param req entity type and ID
     * @param jwt authenticated user JWT
     * @return map with {@code changed} flag and current {@code liked} state
     */
    @PostMapping("/unlike")
    public ResponseEntity<Map<String, Object>> unlike(@Valid @RequestBody ActionRequest req,
                                                      @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        boolean changed = counterService.unlike(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 状态是否发生变化
                "liked", counterService.isLiked(req.getEntityType(), req.getEntityId(), uid)
        ));
    }

    /**
     * Favorites an entity for the authenticated user.
     *
     * @param req entity type and ID
     * @param jwt authenticated user JWT
     * @return map with {@code changed} flag and current {@code faved} state
     */
    @PostMapping("/fav")
    public ResponseEntity<Map<String, Object>> fav(@Valid @RequestBody ActionRequest req,
                                                   @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        boolean changed = counterService.fav(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 状态是否发生变化
                "faved", counterService.isFaved(req.getEntityType(), req.getEntityId(), uid)
        ));
    }

    /**
     * Removes a favorite from an entity for the authenticated user.
     *
     * @param req entity type and ID
     * @param jwt authenticated user JWT
     * @return map with {@code changed} flag and current {@code faved} state
     */
    @PostMapping("/unfav")
    public ResponseEntity<Map<String, Object>> unfav(@Valid @RequestBody ActionRequest req,
                                                     @AuthenticationPrincipal Jwt jwt) {
        long uid = jwtService.extractUserId(jwt);
        boolean changed = counterService.unfav(req.getEntityType(), req.getEntityId(), uid);
        return ResponseEntity.ok(Map.of(
                "changed", changed, // 状态是否发生变化
                "faved", counterService.isFaved(req.getEntityType(), req.getEntityId(), uid)
        ));
    }
}