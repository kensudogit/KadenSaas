package com.kadensaas.web;

import java.util.Map;

import com.kadensaas.service.AiOutboundDemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 自動架電画面向け。サンプルキャンペーンの登録と一覧。
 */
@RestController
@RequestMapping("/api/v1/ai-outbound")
public class AiOutboundController {

    private final AiOutboundDemoService demo;

    public AiOutboundController(AiOutboundDemoService demo) {
        this.demo = demo;
    }

    @GetMapping("/overview")
    public AiOutboundDemoService.Overview overview() {
        return demo.overview(CurrentUser.require());
    }

    /**
     * デモ用キャンペーンと架電先を投入する（冪等）。
     *
     * <p>★ 番号は 03-1234-52xx。実在しない帯域。
     */
    @PostMapping("/seed-demo")
    public ResponseEntity<?> seedDemo() {
        var user = CurrentUser.require();
        var result = demo.seedIfEmpty(user);
        return ResponseEntity.ok(Map.of(
            "overview", result.overview(),
            "created", result.created(),
            "message", result.created()
                ? "AI 架電用サンプルを登録しました"
                : "すでに AI 架電用サンプルがあります"));
    }
}
