package com.kadensaas.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.domain.CallSession;
import com.kadensaas.repository.CallSessionRepository;
import com.kadensaas.repository.DispositionCodeRepository;
import com.kadensaas.service.CallService;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 通話。
 *
 * <p>★ 発信は必ず {@link CallService#requestDial} を通る。ここから
 * Twilio を直接叩く経路は無い（そもそも api に Twilio SDK が入っていない）。
 */
@RestController
@RequestMapping("/api/v1/calls")
public class CallController {

    public record DialRequest(@NotNull UUID phoneId, UUID campaignId, UUID callTargetId) {
    }

    public record DispositionRequest(@NotNull String code, String note) {
    }

    private final CallService callService;
    private final CallSessionRepository calls;
    private final DispositionCodeRepository dispositions;

    public CallController(CallService callService, CallSessionRepository calls,
                          DispositionCodeRepository dispositions) {
        this.callService = callService;
        this.calls = calls;
        this.dispositions = dispositions;
    }

    /**
     * 発信を要求する。
     *
     * <p>★ 関門で止まった場合も 200 を返す。403 や 400 にしないのは、
     * これがエラーではなく「正しく止めた」正常な結果だから。
     * 画面は accepted を見て、理由をそのまま表示すればよい。
     */
    @PostMapping
    public ResponseEntity<?> dial(@RequestBody DialRequest req) {
        var result = callService.requestDial(
            CurrentUser.require(), req.phoneId(), req.campaignId(), req.callTargetId());

        if (!result.accepted()) {
            return ResponseEntity.ok(Map.of(
                "accepted", false,
                "reason", result.blockedReason(),
                "message", result.message()));
        }
        return ResponseEntity.ok(Map.of(
            "accepted", true,
            "callSessionId", result.callSessionId()));
    }

    @PostMapping("/{id}/disposition")
    public ResponseEntity<?> disposition(@PathVariable UUID id,
                                         @RequestBody DispositionRequest req) {
        callService.recordDisposition(CurrentUser.require(), id, req.code(), req.note());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping
    public List<CallSession> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return calls.findAllByOrderByStartedAtDesc(PageRequest.of(page, Math.min(size, 200)))
            .getContent();
    }

    @GetMapping("/{id}")
    public CallSession get(@PathVariable UUID id) {
        return calls.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("通話が見つかりません"));
    }

    /** 結果コードの一覧。画面のボタンをここから作らせる。 */
    @GetMapping("/dispositions")
    public Object dispositionCodes() {
        return dispositions.findAll().stream()
            .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()))
            .map(d -> Map.of(
                "code", d.getCode(),
                "label", d.getLabel(),
                "isDnc", d.isDnc(),
                "isConnected", d.isConnected()))
            .toList();
    }
}
