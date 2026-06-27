package com.chtholly.admin.api.dto;

import com.chtholly.admin.role.Role;
import jakarta.validation.constraints.NotNull;

public record AdminUpdateRoleRequest(
        @NotNull(message = "角色不能为空") Role role
) {}
