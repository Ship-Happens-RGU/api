package com.ocenering.api.auth;

import com.ocenering.api.db.entity.AuditLog;
import com.ocenering.api.db.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditLogRepository auditLogs;

    public AuditService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    public void log(String event, UUID userId, String email, HttpServletRequest req) {
        auditLogs.save(new AuditLog(event, userId, email, req.getRemoteAddr(), req.getHeader("User-Agent")));
    }
}

