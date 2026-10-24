package com.kadensaas.service.callcenter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * ナレッジ出典をコンテキストに、OpenAI でオペレーター向け回答案を作る。
 *
 * <p>★ ヒットが無いときは呼ばない。音声は送らない。学習利用は行わない。
 */
@Component
public class CallCenterOpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(CallCenterOpenAiClient.class);

    private final String apiKey;
    private final String model;
    private final RestClient http;
    private final ObjectMapper json;

    public CallCenterOpenAiClient(
            @Value("${kaden.call-center.openai-api-key:}") String apiKey,
            @Value("${kaden.call-center.openai-model:gpt-4o-mini}") String model,
            ObjectMapper json) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null || model.isBlank() ? "gpt-4o-mini" : model.strip();
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(45));
        this.http = RestClient.builder()
            .baseUrl("https://api.openai.com")
            .requestFactory(factory)
            .build();
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    public Optional<String> compose(
            String inquiry,
            String intent,
            List<CallCenterAssistService.Hit> hits) {
        if (!configured() || hits == null || hits.isEmpty()) {
            return Optional.empty();
        }
        String citations = hits.stream()
            .map(hit -> "#" + hit.chunkId())
            .collect(Collectors.joining(" / "));
        String knowledge = hits.stream()
            .map(hit -> "- 出典 #" + hit.chunkId() + "（" + hit.intent() + "）: " + hit.content())
            .collect(Collectors.joining("\n"));

        String system = """
            あなたはコールセンターのオペレーター支援です。お客様への回答案を日本語で書きます。
            ルール:
            - 与えられたナレッジ以外の事実を追加しない
            - 不明な点は「確認が必要」と書く
            - 冒頭に必ず「出典 %s」を1行目に書く
            - 2行目以降に、オペレーターが読み上げやすい丁寧な回答案（3〜6文程度）
            - マークダウン記法は使わない
            """.formatted(citations);

        String user = """
            分類された意図: %s

            お客様の問い合わせ:
            %s

            参照してよいナレッジ:
            %s
            """.formatted(intent, inquiry, knowledge);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.3);
        body.put("max_tokens", 600);
        body.put("messages", List.of(
            Map.of("role", "system", "content", system),
            Map.of("role", "user", "content", user)));

        try {
            String raw = http.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(String.class);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            JsonNode root = json.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(content.asText().trim());
        } catch (RestClientResponseException ex) {
            log.warn("OpenAI call-center compose failed: status={}", ex.getStatusCode().value());
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("OpenAI call-center compose failed: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
