package com.ebook.auth.repository;

import com.ebook.auth.entity.AuditLog;
import com.ebook.common.repository.BaseRepository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class AuditLogRepository extends BaseRepository<AuditLog, UUID> {
}
