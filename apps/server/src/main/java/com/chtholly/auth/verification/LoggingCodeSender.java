package com.chtholly.auth.verification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 开发/测试用验证码发送器。
 * <p>
 * 不实际发送，写入日志文件 {@code logs/dev-verification.log}（仓库根目录），便于在编辑器中直接打开查看。
 */
@Slf4j
@Component
public class LoggingCodeSender implements CodeSender {

    /** 仓库根目录 logs/dev-verification.log（从 apps/server 运行时 ../../logs） */
    private static Path devLogFile() {
        return Path.of("../../logs/dev-verification.log").toAbsolutePath().normalize();
    }

    @Override
    public void sendCode(VerificationScene scene, String identifier, String code, int expireMinutes) {
        log.info("Send verification code scene={} identifier={} code={} expireMinutes={}", scene, identifier, code, expireMinutes);
        try {
            Path file = devLogFile();
            Files.createDirectories(file.getParent());
            String line = String.format(
                    "%s | scene=%s | %s | code=%s | 有效 %d 分钟%n",
                    Instant.now(), scene, identifier, code, expireMinutes
            );
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("无法写入开发验证码日志 {}", devLogFile(), e);
        }
    }
}
