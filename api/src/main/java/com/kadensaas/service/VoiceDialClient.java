package com.kadensaas.service;

import java.util.Map;
import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * api から voice の内部発信 API を呼ぶ。
 *
 * <p>★ オートダイヤラーはブラウザを介さず queued → dial まで進める。
 */
@Service
public class VoiceDialClient {

    private final JwtService jwt;
    private final RestClient http;
    private final String voiceBaseUrl;

    public VoiceDialClient(JwtService jwt,
                           @Value("${kaden.voice.base-url:}") String voiceBaseUrl) {
        this.jwt = jwt;
        this.voiceBaseUrl = voiceBaseUrl == null ? "" : voiceBaseUrl.strip();
        this.http = RestClient.create();
    }

    public void dialQueuedSession(AuthUser user, UUID callSessionId) {
        if (voiceBaseUrl.isBlank()) {
            throw new IllegalStateException(
                "KADEN_VOICE_BASE_URL が未設定です。オートダイヤラーは voice へ接続できません");
        }
        String token = jwt.issue(user);
        String url = voiceBaseUrl.replaceAll("/$", "")
            + "/internal/calls/" + callSessionId + "/dial";

        http.post()
            .uri(url)
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                throw new IllegalStateException(
                    "voice 発信に失敗しました HTTP " + res.getStatusCode().value());
            })
            .body(Map.class);
    }
}
