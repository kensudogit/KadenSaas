package com.kadensaas.web;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.domain.Callback;
import com.kadensaas.repository.CallbackRepository;
import com.kadensaas.service.AuditService;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/callbacks")
public class CallbackController {

    /**
     * ★ scheduledAt はタイムゾーン付きで受け取る。
     * 「14:00」だけを受け取ると、どこの 14 時か決まらない。
     * 架電時間帯の判定がここに依存するので、曖昧なまま保存しない。
     */
    public record CreateRequest(@NotNull UUID customerId, UUID callSessionId,
                                @NotNull OffsetDateTime scheduledAt,
                                String reason, UUID assignedTo) {
    }

    private final CallbackRepository callbacks;
    private final AuditService audit;

    public CallbackController(CallbackRepository callbacks, AuditService audit) {
        this.callbacks = callbacks;
        this.audit = audit;
    }

    @GetMapping
    public List<Callback> list(@RequestParam(defaultValue = "open") String status) {
        return callbacks.findByStatusOrderByScheduledAtAsc(status);
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody CreateRequest req) {
        var user = CurrentUser.require();

        Callback cb = new Callback();
        cb.setTenantId(user.tenantId());
        cb.setCustomerId(req.customerId());
        cb.setCallSessionId(req.callSessionId());
        cb.setScheduledAt(req.scheduledAt());
        cb.setReason(req.reason());
        cb.setAssignedTo(req.assignedTo() != null ? req.assignedTo() : user.userId());
        cb.setStatus("open");
        callbacks.save(cb);

        audit.record(user, "callback.created", "callback", cb.getId());
        return Map.of("id", cb.getId());
    }

    @PatchMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable UUID id, @RequestParam String status) {
        Callback cb = callbacks.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("再架電予定が見つかりません"));
        cb.setStatus(status);
        if ("done".equals(status)) {
            cb.setCompletedAt(OffsetDateTime.now());
        }
        callbacks.save(cb);
        return Map.of("ok", true);
    }
}
