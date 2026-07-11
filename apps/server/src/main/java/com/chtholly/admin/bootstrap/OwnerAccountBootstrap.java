package com.chtholly.admin.bootstrap;

import com.chtholly.admin.role.Role;
import com.chtholly.auth.util.IdentifierValidator;
import com.chtholly.config.SiteProperties;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动时根据环境变量同步站长账号（handle、昵称、密码、ADMIN 角色）。
 * <p>
 * 密码仅通过 {@code OWNER_BOOTSTRAP_PASSWORD} 注入，勿写入代码库或 seed SQL。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "seed.cli-read-only", havingValue = "false", matchIfMissing = true)
@Order(5)
@RequiredArgsConstructor
public class OwnerAccountBootstrap implements ApplicationRunner {

    private final SiteProperties siteProperties;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        String rawPassword = siteProperties.ownerBootstrapPassword();
        if (!StringUtils.hasText(rawPassword)) {
            return;
        }

        long ownerId = siteProperties.ownerUserId();
        User existing = userMapper.findById(ownerId);
        if (existing == null) {
            log.warn("owner.bootstrap owner userId={} not found; import db/seed/phase_a_seed.sql first", ownerId);
            return;
        }

        String handle = siteProperties.ownerHandle().trim();
        String nickname = siteProperties.ownerNickname().trim();
        if (!IdentifierValidator.isValidHandle(handle)) {
            log.warn("owner.bootstrap invalid handle={}; skip profile sync", handle);
            return;
        }

        userMapper.updateProfile(User.builder()
                .id(ownerId)
                .handle(handle)
                .nickname(nickname)
                .bio("动漫 · 追番 · 随笔")
                .build());

        if (!StringUtils.hasText(existing.getPasswordHash())) {
            userMapper.updatePassword(ownerId, passwordEncoder.encode(rawPassword.trim()));
            log.info("owner.bootstrap set password for owner userId={} handle={}", ownerId, handle);
        } else {
            log.info("owner.bootstrap password already configured for userId={}, skip password sync", ownerId);
        }

        userMapper.updateRole(ownerId, Role.ADMIN.name());
        log.info("owner.bootstrap synced owner profile userId={} handle={}", ownerId, handle);
    }
}
