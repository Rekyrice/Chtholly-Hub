package com.chtholly.admin.mapper;

import com.chtholly.admin.model.AdminAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminAuditMapper {
    void insert(AdminAuditLog log);
}
