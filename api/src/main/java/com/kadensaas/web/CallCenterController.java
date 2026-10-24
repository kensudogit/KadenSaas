package com.kadensaas.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.service.callcenter.CallCenterAssistService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

/**
 * 着信オペレーター支援。
 *
 * <p>★ 発信 API ではない。ここを叩いても {@code call_sessions} は増えない。
 */
@RestController
@RequestMapping("/api/v1/call-center")
public class CallCenterController {

    public record AssistRequest(@NotBlank String text, UUID customerId) {
    }

    private final CallCenterAssistService assist;

    public CallCenterController(CallCenterAssistService assist) {
        this.assist = assist;
    }

    @PostMapping("/assist")
    public CallCenterAssistService.AssistResult assist(@Valid @RequestBody AssistRequest req) {
        return assist.assist(CurrentUser.require(), req.text(), req.customerId());
    }

    @GetMapping("/inquiries")
    public List<CallCenterAssistService.InquiryRow> inquiries(
            @RequestParam(defaultValue = "30") int limit) {
        return assist.listInquiries(limit);
    }

    @DeleteMapping("/inquiries/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        if (!assist.deleteInquiry(CurrentUser.require(), id)) {
            throw new IllegalArgumentException("受付が見つかりません");
        }
        return Map.of("ok", true);
    }

    @GetMapping("/knowledge")
    public List<CallCenterAssistService.ArticleRow> knowledge() {
        return assist.listKnowledge();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "openaiConfigured", assist.openAiConfigured(),
            "openAiModel", assist.openAiModelName(),
            "trainingUse", false,
            "note", "inbound_assist_not_outbound_dialer");
    }

    /** FAQ と受付履歴のデモ。発信しない。オペレーターでも画面確認できるようにする。 */
    @PostMapping("/seed-demo")
    public CallCenterAssistService.DemoSeedResult seedDemo() {
        return assist.seedDemoIfEmpty(CurrentUser.require());
    }
}
