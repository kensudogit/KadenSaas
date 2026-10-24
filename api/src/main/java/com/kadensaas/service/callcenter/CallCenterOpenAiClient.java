package com.kadensaas.service.callcenter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * <p>★ ヒットが無いときは compose を呼ばない。音声は送らない。学習利用は行わない。
 */
@Component
public class CallCenterOpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(CallCenterOpenAiClient.class);

    private static final Set<String> ALLOWED_INTENTS = Set.of(
        "billing", "shipping", "account", "complaint", "general");

    public record RagCandidate(UUID chunkId, String intent, String excerpt) {
    }

    public record ComposeResult(String reply, List<String> operatorChecklist) {
    }

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

    public String modelName() {
        return model;
    }

    /**
     * キーワード分類が general / 弱いとき、許可ラベルだけから意図を選ぶ。
     */
    public Optional<IntentClassifier.Result> refineIntent(String inquiry) {
        if (!configured()) {
            return Optional.empty();
        }
        String system = """
            コールセンター問い合わせの意図を1つ選び、JSONだけ返す。
            intent は次のいずれかのみ: billing, shipping, account, complaint, general
            形式: {"intent":"billing"}
            不明なら general。
            """;
        Optional<JsonNode> node = chatJson(system, inquiry, 0.1, 80);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        String intent = node.get().path("intent").asText("general").strip();
        if (!ALLOWED_INTENTS.contains(intent)) {
            intent = "general";
        }
        return Optional.of(new IntentClassifier.Result(
            intent,
            1.0,
            "openai",
            "openai_intent_not_measured_accuracy"));
    }

    /**
     * 語彙 RAG が空のとき、候補 ID のみから関連チャンクを選ぶ（捏造防止）。
     */
    public List<UUID> selectChunkIds(String inquiry, String intent, List<RagCandidate> candidates) {
        if (!configured() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int cap = Math.min(candidates.size(), 48);
        StringBuilder catalog = new StringBuilder();
        for (int i = 0; i < cap; i++) {
            RagCandidate c = candidates.get(i);
            catalog.append("- id=").append(c.chunkId())
                .append(" intent=").append(c.intent())
                .append(" text=").append(c.excerpt())
                .append("\n");
        }
        String system = """
            問い合わせに答えられるナレッジ chunk の id だけを選ぶ。JSONのみ返す。
            形式: {"chunkIds":["uuid",...]}
            ルール:
            - 候補リストの id 以外は返さない
            - 関連が無い・不確かなら chunkIds は空配列
            - 最大3件
            """;
        String user = """
            分類意図: %s

            問い合わせ:
            %s

            候補:
            %s
            """.formatted(intent, inquiry, catalog);
        Optional<JsonNode> node = chatJson(system, user, 0.0, 200);
        if (node.isEmpty()) {
            return List.of();
        }
        JsonNode ids = node.get().path("chunkIds");
        if (!ids.isArray()) {
            return List.of();
        }
        Set<UUID> allowed = candidates.stream()
            .map(RagCandidate::chunkId)
            .collect(Collectors.toSet());
        List<UUID> picked = new ArrayList<>();
        for (JsonNode idNode : ids) {
            if (picked.size() >= 3) {
                break;
            }
            try {
                UUID id = UUID.fromString(idNode.asText().strip());
                if (allowed.contains(id) && !picked.contains(id)) {
                    picked.add(id);
                }
            } catch (IllegalArgumentException ignored) {
                // skip invalid id from model
            }
        }
        return picked;
    }

    public Optional<ComposeResult> compose(
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
            あなたはコールセンターのオペレーター支援です。JSONのみ返す。
            形式:
            {"reply":"...","operatorChecklist":["..."]}

            reply のルール:
            - 1行目は必ず「出典 %s」
            - 2行目以降は読み上げやすい丁寧な回答案（3〜6文）。マークダウン禁止
            - 与えられたナレッジ以外の事実を追加しない
            - 不明は「確認が必要」

            operatorChecklist:
            - オペレーターが通話中に確認すべき項目を0〜5個（短い日本語）
            - ナレッジに無い項目を事実として書かない
            """.formatted(citations);

        String user = """
            分類された意図: %s

            お客様の問い合わせ:
            %s

            参照してよいナレッジ:
            %s
            """.formatted(intent, inquiry, knowledge);

        Optional<JsonNode> node = chatJson(system, user, 0.3, 700);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        JsonNode root = node.get();
        String reply = root.path("reply").asText("").strip();
        if (reply.isBlank()) {
            return Optional.empty();
        }
        List<String> checklist = new ArrayList<>();
        JsonNode arr = root.path("operatorChecklist");
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String line = item.asText("").strip();
                if (!line.isEmpty() && checklist.size() < 5) {
                    checklist.add(line);
                }
            }
        }
        return Optional.of(new ComposeResult(reply, List.copyOf(checklist)));
    }

    /**
     * FAQ に該当が無いときの一般論ベースの下書き。事実の断定は避ける。
     */
    public Optional<ComposeResult> composeWithoutKnowledge(String inquiry, String intent) {
        if (!configured()) {
            return Optional.empty();
        }
        String system = """
            コールセンターのオペレーター支援です。社内FAQに該当が無い問い合わせ向けに、
            一般論のみの回答下書きを JSON で返してください。
            形式:
            {"reply":"...","operatorChecklist":["..."]}

            reply のルール:
            - 1行目は必ず「出典 該当なし（AI下書き・未確認）」
            - 2行目以降: 読み上げやすい丁寧な案内（3〜6文）。マークダウン禁止
            - 具体的な金額・日付・社内ポリシー・返金可否を断定しない
            - 「確認のうえ折り返す」「上長・担当部署へエスカレーション」など安全な案内に留める
            - 不明点は「確認が必要」

            operatorChecklist:
            - 通話中に確認すべき項目を0〜5個（短い日本語）
            """;
        String user = """
            分類された意図: %s

            お客様の問い合わせ:
            %s
            """.formatted(intent, inquiry);

        Optional<JsonNode> node = chatJson(system, user, 0.4, 700);
        if (node.isEmpty()) {
            return Optional.empty();
        }
        JsonNode root = node.get();
        String reply = root.path("reply").asText("").strip();
        if (reply.isBlank()) {
            return Optional.empty();
        }
        List<String> checklist = new ArrayList<>();
        JsonNode arr = root.path("operatorChecklist");
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String line = item.asText("").strip();
                if (!line.isEmpty() && checklist.size() < 5) {
                    checklist.add(line);
                }
            }
        }
        return Optional.of(new ComposeResult(reply, List.copyOf(checklist)));
    }

    private Optional<JsonNode> chatJson(String system, String user, double temperature, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("response_format", Map.of("type", "json_object"));
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
            return Optional.of(json.readTree(content.asText()));
        } catch (RestClientResponseException ex) {
            log.warn("OpenAI call-center JSON call failed: status={}", ex.getStatusCode().value());
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("OpenAI call-center JSON call failed: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
