package com.chtholly.admin.api;

import com.chtholly.admin.api.dto.AdminUpdateVisibilityRequest;
import com.chtholly.admin.role.RequireRole;
import com.chtholly.admin.role.Role;
import com.chtholly.admin.service.AdminPostService;
import com.chtholly.auth.token.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin 内容管理接口。 */
@RestController
@RequestMapping("/api/v1/admin/posts")
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class AdminPostController {

    private final AdminPostService adminPostService;
    private final JwtService jwtService;

    @PatchMapping("/{id}/visibility")
    public ResponseEntity<Void> updateVisibility(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable long id,
                                               @Valid @RequestBody AdminUpdateVisibilityRequest request) {
        adminPostService.updateVisibility(jwtService.extractUserId(jwt), id, request.visible());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePost(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        adminPostService.deletePost(jwtService.extractUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{postId}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(@AuthenticationPrincipal Jwt jwt,
                                              @PathVariable long postId,
                                              @PathVariable long commentId) {
        adminPostService.deleteComment(jwtService.extractUserId(jwt), postId, commentId);
        return ResponseEntity.noContent().build();
    }
}
