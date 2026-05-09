package com.e.demo.Controllers;

import com.e.demo.entity.Case;
import com.e.demo.entity.CaseSignoff;
import com.e.demo.entity.Job;
import com.e.demo.entity.Slide;
import com.e.demo.repository.CaseRepository;
import com.e.demo.repository.CaseSignoffRepository;
import com.e.demo.repository.JobRepository;
import com.e.demo.repository.SlideRepository;
import com.e.demo.server.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * E5: REST API для клинических кейсов (группа слайдов одного пациента).
 * E6/E8: sign-off, soft-delete (409 если кейс уже подписан).
 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseRepository caseRepo;
    private final SlideRepository slideRepo;
    private final JobRepository jobRepo;
    private final CaseSignoffRepository signoffRepo;
    private final AuditService audit;

    public CaseController(CaseRepository caseRepo,
                          SlideRepository slideRepo,
                          JobRepository jobRepo,
                          CaseSignoffRepository signoffRepo,
                          AuditService audit) {
        this.caseRepo = caseRepo;
        this.slideRepo = slideRepo;
        this.jobRepo = jobRepo;
        this.signoffRepo = signoffRepo;
        this.audit = audit;
    }

    private Integer currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new RuntimeException("Not authenticated");
        }
        return (Integer) auth.getPrincipal();
    }

    /** Список активных кейсов текущего пользователя. */
    @GetMapping
    public List<Map<String, Object>> list() {
        Integer uid = currentUserId();
        return caseRepo.findActiveByUser(uid).stream().map(this::toView).toList();
    }

    /** Детали кейса со списком слайдов. */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Integer id) {
        Integer uid = currentUserId();
        Case c = caseRepo.findActiveById(id).orElse(null);
        if (c == null || !c.getUserId().equals(uid)) return ResponseEntity.notFound().build();

        Map<String, Object> view = toView(c);
        List<Slide> slides = slideRepo.findActiveByCaseId(id);
        view.put("slides", slides.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("filename", s.getFilename());
            m.put("biopsyLocation", s.getBiopsyLocation());
            m.put("status", s.getStatus());
            Job job = jobRepo.findFirstBySlideIdOrderByCreatedAtDesc(s.getId()).orElse(null);
            if (job != null) {
                m.put("jobId", job.getId());
                m.put("jobStatus", job.getStatus());
                m.put("diagnosis", job.getDiagnosis());
                m.put("totalEosinophils", job.getTotalEosinophilCount());
                m.put("maxHpfCount", job.getMaxHpfCount());
                m.put("modelVersion", job.getModelVersion());
            }
            return m;
        }).toList());

        signoffRepo.findFirstByCaseIdOrderBySignedAtDesc(id).ifPresent(so -> {
            Map<String, Object> sign = new LinkedHashMap<>();
            sign.put("id", so.getId());
            sign.put("userId", so.getUserId());
            sign.put("diagnosis", so.getDiagnosis());
            sign.put("comments", so.getComments());
            sign.put("signedAt", so.getSignedAt());
            view.put("signoff", sign);
        });

        return ResponseEntity.ok(view);
    }

    /** Создание нового кейса. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        Integer uid = currentUserId();
        Case c = new Case();
        c.setUserId(uid);
        c.setPatientId(body.getOrDefault("patientId", ""));
        c.setName(body.get("name"));
        c.setDescription(body.get("description"));
        c.setStatus("OPEN");
        caseRepo.save(c);

        audit.log(uid, "CREATE_CASE", "CASE", c.getId(),
            Map.of("patientId", c.getPatientId(), "name", String.valueOf(c.getName())));

        return ResponseEntity.ok(toView(c));
    }

    /** E6/E8: sign-off — подпись кейса патоморфологом. */
    @PostMapping("/{id}/signoff")
    public ResponseEntity<?> signoff(@PathVariable Integer id,
                                     @RequestBody Map<String, String> body) {
        Integer uid = currentUserId();
        Case c = caseRepo.findActiveById(id).orElse(null);
        if (c == null || !c.getUserId().equals(uid)) return ResponseEntity.notFound().build();
        if ("SIGNED_OFF".equals(c.getStatus())) {
            return ResponseEntity.status(409).body(Map.of("error", "Case already signed off"));
        }

        // Собираем версии моделей по слайдам
        List<Slide> slides = slideRepo.findActiveByCaseId(id);
        StringBuilder versions = new StringBuilder("[");
        boolean first = true;
        for (Slide s : slides) {
            Job job = jobRepo.findFirstBySlideIdOrderByCreatedAtDesc(s.getId()).orElse(null);
            if (!first) versions.append(",");
            versions.append("\"").append(job != null && job.getModelVersion() != null
                    ? job.getModelVersion() : "unknown").append("\"");
            first = false;
        }
        versions.append("]");

        String diagnosis = body.getOrDefault("diagnosis", "POSITIVE");

        CaseSignoff so = new CaseSignoff();
        so.setCaseId(id);
        so.setUserId(uid);
        so.setDiagnosis(diagnosis);
        so.setModelVersions(versions.toString());
        so.setComments(body.get("comments"));
        signoffRepo.save(so);

        caseRepo.updateStatus(id, "SIGNED_OFF", diagnosis, Instant.now());

        audit.log(uid, "SIGNOFF", "CASE", id,
            Map.of("diagnosis", diagnosis, "slideCount", slides.size()));

        return ResponseEntity.ok(Map.of(
            "caseId", id,
            "diagnosis", diagnosis,
            "signedAt", so.getSignedAt()
        ));
    }

    /** E8: soft-delete кейса. 409 если уже подписан. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        Integer uid = currentUserId();
        Case c = caseRepo.findActiveById(id).orElse(null);
        if (c == null || !c.getUserId().equals(uid)) return ResponseEntity.notFound().build();
        if (signoffRepo.existsByCaseId(id) || "SIGNED_OFF".equals(c.getStatus())) {
            return ResponseEntity.status(409)
                .body(Map.of("error", "Cannot delete signed-off case"));
        }
        caseRepo.softDelete(id, Instant.now());
        audit.log(uid, "DELETE_CASE", "CASE", id, null);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> toView(Case c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("patientId", c.getPatientId());
        m.put("name", c.getName());
        m.put("description", c.getDescription());
        m.put("status", c.getStatus());
        m.put("diagnosis", c.getDiagnosis());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }
}
