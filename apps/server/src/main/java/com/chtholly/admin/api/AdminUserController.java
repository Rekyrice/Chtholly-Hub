package com.chtholly.admin.api;

import com.chtholly.admin.api.dto.AdminUpdateRoleRequest;
import com.chtholly.admin.api.dto.AdminUserPageResponse;
import com.chtholly.admin.role.RequireRole;
import com.chtholly.admin.role.Role;
import com.chtholly.admin.service.AdminUserService;
import com.chtholly.auth.token.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin 用户管理接口。 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final JwtService jwtService;

    @GetMapping
    public AdminUserPageResponse listUsers(@RequestParam(required = false) String keyword,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return adminUserService.listUsers(keyword, page, size);
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<Void> updateRole(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable long id,
                                           @Valid @RequestBody AdminUpdateRoleRequest request) {
        adminUserService.updateRole(jwtService.extractUserId(jwt), id, request.role());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/ban")
    public ResponseEntity<Void> banUser(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        adminUserService.banUser(jwtService.extractUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/ban")
    public ResponseEntity<Void> unbanUser(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        adminUserService.unbanUser(jwtService.extractUserId(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
