package com.ebook.auth.service;

import com.ebook.auth.entity.AuditLog;
import com.ebook.auth.enums.EventType;
import com.ebook.auth.repository.AuditLogRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.UUID;

@ApplicationScoped
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void logEvent(UUID userId, EventType eventType, String ipAddress, String metadataJson) {
        AuditLog log = new AuditLog();
        log.setUserId(userId);
        log.setEventType(eventType);
        log.setIpAddress(ipAddress);
        log.setMetadata(metadataJson);
        auditLogRepository.save(log);
    }
}
