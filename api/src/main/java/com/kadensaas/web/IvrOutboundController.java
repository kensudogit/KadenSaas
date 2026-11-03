package com.kadensaas.web;

import java.util.List;
import java.util.Map;

import com.kadensaas.service.AiOutboundDemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ivr-outbound")
public class IvrOutboundController {

    private final AiOutboundDemoService demo;

    public IvrOutboundController(AiOutboundDemoService demo) {
        this.demo = demo;
    }

    @GetMapping("/overview")
    public AiOutboundDemoService.Overview overview() {
        return demo.ivrOverview(CurrentUser.require());
    }

    @PostMapping("/seed-demo")
    public ResponseEntity<?> seedDemo() {
        var result = demo.seedIvrIfEmpty(CurrentUser.require());
        return ResponseEntity.ok(Map.of(
            "overview", result.overview(),
            "created", result.created(),
            "message", result.created()
                ? "IVR 架電用サンプルを登録しました"
                : "すでに IVR 架電用サンプルがあります"));
    }
}
