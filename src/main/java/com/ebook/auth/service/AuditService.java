package com.ebook.auth.service;

import com.ebook.auth.entity.AuditLog;
import com.ebook.auth.enums.EventType;
import com.ebook.auth.repository.AuditLogRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class AuditService {

    private static final Logger LOG = Logger.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Writes an audit row in its own transaction. Best-effort: if the audit table is
     * unavailable (disk full, lock timeout, column drift), the main flow continues and
     * the failure is logged at ERROR so ops can alert on it. Killing a login/checkout
     * because the audit table is wedged is worse than a missing audit row.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void logEvent(UUID userId, EventType eventType, String ipAddress, String metadataJson) {
        try {
            AuditLog log = new AuditLog();
            log.setUserId(userId);
            log.setEventType(eventType);
            log.setIpAddress(ipAddress);
            log.setMetadata(metadataJson);
            auditLogRepository.save(log);
        } catch (Exception e) {
            LOG.errorf(e, "Audit write failed — user=%s event=%s ip=%s (continuing without audit)",
                    userId, eventType, ipAddress);
        }
    }
}
