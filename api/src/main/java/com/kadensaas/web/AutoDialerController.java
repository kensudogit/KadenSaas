package com.kadensaas.web;

import java.util.Map;
import java.util.UUID;

import com.kadensaas.service.AiOutboundDemoService;
import com.kadensaas.service.AutoDialerService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auto-dialer")
public class AutoDialerController {

    public record StartRequest(UUID campaignId, String conversationMode) {
    }

    private final AutoDialerService autoDialer;
    private final AiOutboundDemoService demo;

    public AutoDialerController(AutoDialerService autoDialer, AiOutboundDemoService demo) {
        this.autoDialer = autoDialer;
        this.demo = demo;
    }

    @GetMapping("/campaigns")
    public Object campaigns() {
        return demo.listAutoDialerCampaigns(CurrentUser.require());
    }

    @PostMapping("/seed-demo")
    public Object seedDemo() {
        var result = demo.seedAutoDialerIfEmpty(CurrentUser.require());
        return Map.of(
            "overview", result.overview(),
            "created", result.created(),
            "message", result.created()
                ? "オートダイヤラー用サンプルを登録しました"
                : "すでにオートダイヤラー用サンプルがあります");
    }

    @GetMapping("/runs")
    public Object listRuns() {
        return autoDialer.listRuns(CurrentUser.require());
    }

    @PostMapping("/runs")
    public AutoDialerService.RunView start(@RequestBody StartRequest req) {
        if (req == null || req.campaignId() == null) {
            throw new IllegalArgumentException("campaignId が必要です");
        }
        return autoDialer.start(
            CurrentUser.require(), req.campaignId(), req.conversationMode());
    }

    @PostMapping("/runs/{id}/tick")
    public AutoDialerService.TickResult tick(@PathVariable UUID id) {
        return autoDialer.tick(CurrentUser.require(), id);
    }

    @PostMapping("/runs/{id}/pause")
    public Map<String, Object> pause(@PathVariable UUID id) {
        autoDialer.pause(id);
        return Map.of("ok", true);
    }

    @PostMapping("/runs/{id}/resume")
    public Map<String, Object> resume(@PathVariable UUID id) {
        autoDialer.resume(id);
        return Map.of("ok", true);
    }

    @PostMapping("/runs/{id}/stop")
    public Map<String, Object> stop(@PathVariable UUID id) {
        autoDialer.stop(id);
        return Map.of("ok", true);
    }
}
