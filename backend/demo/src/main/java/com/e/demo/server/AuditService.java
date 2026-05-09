package com.e.demo.server;

import com.e.demo.entity.AuditEvent;
import com.e.demo.repository.AuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class AuditService {

    private final AuditEventRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditService(AuditEventRepository repo) {
        this.repo = repo;
    }

    public void log(Integer userId, String action, String entityType, Object entityId, Map<String, Object> payload) {
        try {
            AuditEvent ev = new AuditEvent();
            ev.setUserId(userId);
            ev.setAction(action);
            ev.setEntityType(entityType);
            ev.setEntityId(String.valueOf(entityId));
            ev.setPayload(payload == null ? null : toJson(payload));
            repo.save(ev);
        } catch (Exception e) {
            // Аудит не должен ронять основную операцию
            log.error("Audit log failed: action={} entity={}/{}", action, entityType, entityId, e);
        }
    }

    private String toJson(Map<String, Object> payload) throws JsonProcessingException {
        return mapper.writeValueAsString(payload);
    }
}
