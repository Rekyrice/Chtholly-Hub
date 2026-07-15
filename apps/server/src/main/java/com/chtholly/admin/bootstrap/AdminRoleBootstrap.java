package com.chtholly.admin.bootstrap;

import com.chtholly.admin.role.Role;
import com.chtholly.config.SiteProperties;
import com.chtholly.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时确保站长（site.owner-user-id）拥有 ADMIN 角色。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "seed.cli-read-only", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class AdminRoleBootstrap implements ApplicationRunner {

    private final SiteProperties siteProperties;
    private final UserMapper userMapper;

    @Override
    public void run(ApplicationArguments args) {
        long ownerId = siteProperties.ownerUserId();
        int updated = userMapper.updateRole(ownerId, Role.ADMIN.name());
        if (updated > 0) {
            log.info("admin.bootstrap promoted owner userId={} to ADMIN", ownerId);
        } else if (userMapper.findById(ownerId) == null) {
            log.warn("admin.bootstrap owner userId={} not found in users table", ownerId);
        }
    }
}
