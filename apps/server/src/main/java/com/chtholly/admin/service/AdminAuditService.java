package com.chtholly.admin.service;

import com.chtholly.admin.audit.AdminAction;
import com.chtholly.admin.mapper.AdminAuditMapper;
import com.chtholly.admin.model.AdminAuditLog;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/** 管理员操作审计日志。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final AdminAuditMapper auditMapper;
    private final SnowflakeIdGenerator idGen;
    private final ObjectMapper objectMapper;

    public void record(long adminUserId, AdminAction action, String targetType, Long targetId, Map<String, Object> detail) {
        try {
            AdminAuditLog row = AdminAuditLog.builder()
                    .id(idGen.nextId())
                    .adminUserId(adminUserId)
                    .action(action.name())
                    .targetType(targetType)
                    .targetId(targetId)
                    .detailJson(detail == null || detail.isEmpty() ? null : objectMapper.writeValueAsString(detail))
                    .createdAt(Instant.now())
                    .build();
            auditMapper.insert(row);
        } catch (JsonProcessingException e) {
            log.warn("admin.audit serialize failed action={}: {}", action, e.getMessage());
        } catch (Exception e) {
            log.warn("admin.audit insert failed action={}: {}", action, e.getMessage());
        }
    }
}
