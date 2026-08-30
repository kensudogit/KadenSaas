package com.kadensaas.web;

import java.util.List;
import java.util.Map;

import com.kadensaas.domain.DoNotCallEntry;
import com.kadensaas.repository.DoNotCallRepository;
import com.kadensaas.service.CallService;
import com.kadensaas.service.PhoneNumbers;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 再勧誘拒否リスト。
 *
 * <p>★ 登録は誰でもできるが、削除は manager 以上に限る。
 * 「間違えて登録した」より「消したから再びかかった」のほうが重い。
 * 非対称にしておく。
 */
@RestController
@RequestMapping("/api/v1/dnc")
public class DncController {

    public record RegisterRequest(@NotBlank String phone, String reason) {
    }

    private final DoNotCallRepository dnc;
    private final CallService callService;

    public DncController(DoNotCallRepository dnc, CallService callService) {
        this.dnc = dnc;
        this.callService = callService;
    }

    @GetMapping
    public List<DoNotCallEntry> list() {
        return dnc.findAll();
    }

    @PostMapping
    public Map<String, Object> register(@RequestBody RegisterRequest req) {
        String e164 = PhoneNumbers.toE164Jp(req.phone());
        callService.registerDnc(CurrentUser.require(), e164,
            req.reason() != null ? req.reason() : "手動登録");
        return Map.of("ok", true, "e164", e164);
    }

    @DeleteMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    @Transactional
    public Map<String, Object> remove(@RequestParam String phone) {
        String e164 = PhoneNumbers.toE164Jp(phone);
        dnc.deleteByE164(e164);
        return Map.of("ok", true);
    }

    /** 発信前の照会。画面が発信ボタンを出す前に使う。 */
    @GetMapping("/check")
    public Map<String, Object> check(@RequestParam String phone) {
        String e164 = PhoneNumbers.toE164Jp(phone);
        return Map.of("e164", e164, "blocked", dnc.existsByE164(e164));
    }
}
