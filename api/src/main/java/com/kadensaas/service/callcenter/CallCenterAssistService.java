package com.kadensaas.service.callcenter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.service.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 着信問い合わせの回答案。
 *
 * <p>★ 発信しない。{@code call_sessions} も作らない。
 * DialingGate を通らない経路から電話を鳴らさないための境界。
 *
 * <p>★ RAG ヒット時はチャンクを出典にする。ヒット無しで OpenAI 有効時は
 * 「出典 該当なし（AI下書き）」として一般論の下書きを作れる（設定で無効化可）。
 */
@Service
public class CallCenterAssistService {

    public record Hit(UUID chunkId, UUID articleId, String content, String intent, int score) {
    }

    public record AssistResult(
        UUID id,
        String transcript,
        String intent,
        IntentClassifier.Result nlp,
        List<Hit> hits,
        String reply,
        List<String> operatorChecklist,
        boolean ragGrounded,
        boolean trainingUse,
        String note
    ) {
    }

    public record InquiryRow(
        UUID id,
        String transcript,
        String intent,
        String reply,
        String sources,
        String createdAt
    ) {
    }

    public record ArticleRow(UUID id, String title, String body, String intent) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CallCenterOpenAiClient openAi;
    private final boolean aiDraftWithoutRag;

    public CallCenterAssistService(
            JdbcTemplate jdbc,
            AuditService audit,
            CallCenterOpenAiClient openAi,
            @org.springframework.beans.factory.annotation.Value(
                "${kaden.call-center.ai-draft-without-rag:true}") boolean aiDraftWithoutRag) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.openAi = openAi;
        this.aiDraftWithoutRag = aiDraftWithoutRag;
    }

    public boolean openAiConfigured() {
        return openAi.configured();
    }

    public String openAiModelName() {
        return openAi.modelName();
    }

    @Transactional
    public AssistResult assist(AuthUser user, String text, UUID customerId) {
        String transcript = text == null ? "" : text.trim();
        if (transcript.isEmpty()) {
            throw new IllegalArgumentException("問い合わせ本文が空です");
        }
        if (transcript.length() > 8000) {
            throw new IllegalArgumentException("問い合わせ本文が長すぎます");
        }

        IntentClassifier.Result keywordNlp = IntentClassifier.classify(transcript);
        IntentClassifier.Result nlp = refineNlp(transcript, keywordNlp);
        // ★ Railway 等で FAQ 未投入のテナントでも、初回から回答案が試せるようにする
        KnowledgeCatalog.seedIfEmpty(jdbc, user.tenantId());
        List<Hit> hits = search(transcript, nlp.intent());
        String ragNote = null;
        if (hits.isEmpty()) {
            hits = intentAlignedHits(nlp.intent(), keywordNlp.score());
            if (!hits.isEmpty()) {
                ragNote = "intent_aligned_rag_not_measured_accuracy";
            }
        }
        if (hits.isEmpty() && openAi.configured()) {
            var selected = openAiSelectHits(transcript, nlp.intent());
            if (!selected.isEmpty()) {
                hits = selected;
                ragNote = "openai_chunk_select_not_measured_accuracy";
            }
        }
        Draft draft = buildReply(transcript, nlp.intent(), hits, ragNote);
        String reply = draft.reply();
        String sources = hits.stream()
            .map(hit -> "\"" + hit.chunkId() + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        UUID id = jdbc.queryForObject("""
            insert into inbound_inquiries
              (tenant_id, customer_id, operator_id, transcript, intent, reply, sources)
            values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
            returning id
            """,
            UUID.class,
            user.tenantId(),
            customerId,
            user.userId(),
            transcript,
            nlp.intent(),
            reply,
            sources);

        audit.record(user, "call_center.assist", "inbound_inquiry", id);
        return new AssistResult(
            id,
            transcript,
            nlp.intent(),
            nlp,
            hits,
            reply,
            draft.operatorChecklist(),
            !hits.isEmpty(),
            false,
            draft.note());
    }

    private record Draft(String reply, String note, List<String> operatorChecklist) {
    }

    private IntentClassifier.Result refineNlp(String transcript, IntentClassifier.Result keyword) {
        if (!openAi.configured()) {
            return keyword;
        }
        if (!"general".equals(keyword.intent()) && keyword.score() >= 2) {
            return keyword;
        }
        return openAi.refineIntent(transcript).orElse(keyword);
    }

    private List<Hit> intentAlignedHits(String intent, double keywordScore) {
        if (keywordScore < 1 || intent == null || "general".equals(intent)) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select id, article_id, content, intent from knowledge_chunks
             where intent = ?
             order by id
             limit 3
            """, intent);
        List<Hit> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            hits.add(new Hit(
                (UUID) row.get("id"),
                (UUID) row.get("article_id"),
                String.valueOf(row.get("content")),
                String.valueOf(row.get("intent")),
                1));
        }
        return hits;
    }

    private List<Hit> openAiSelectHits(String transcript, String intent) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select id, article_id, content, intent from knowledge_chunks
             order by intent, id
             limit 48
            """);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<CallCenterOpenAiClient.RagCandidate> candidates = new ArrayList<>();
        Map<UUID, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            String content = String.valueOf(row.get("content"));
            String excerpt = content.length() > 220 ? content.substring(0, 220) + "…" : content;
            candidates.add(new CallCenterOpenAiClient.RagCandidate(
                id,
                String.valueOf(row.get("intent")),
                excerpt));
            byId.put(id, row);
        }
        List<UUID> picked = openAi.selectChunkIds(transcript, intent, candidates);
        List<Hit> hits = new ArrayList<>();
        for (UUID id : picked) {
            Map<String, Object> row = byId.get(id);
            if (row == null) {
                continue;
            }
            hits.add(new Hit(
                id,
                (UUID) row.get("article_id"),
                String.valueOf(row.get("content")),
                String.valueOf(row.get("intent")),
                2));
        }
        return hits;
    }

    private Draft buildReply(String transcript, String intent, List<Hit> hits, String ragNote) {
        if (hits.isEmpty()) {
            if (openAi.configured() && aiDraftWithoutRag) {
                return openAi.composeWithoutKnowledge(transcript, intent)
                    .map(result -> new Draft(
                        ensureNoRagBanner(result.reply()),
                        "openai_no_rag_draft_not_measured_accuracy",
                        result.operatorChecklist()))
                    .orElseGet(() -> new Draft(
                        draftTemplate(intent, hits),
                        "openai_no_rag_failed",
                        List.of()));
            }
            return new Draft(
                draftTemplate(intent, hits),
                "lexical_rag_no_hits",
                List.of());
        }
        String retrievalNote = ragNote != null ? ragNote : "lexical_rag_not_measured_accuracy";
        if (openAi.configured()) {
            return openAi.compose(transcript, intent, hits)
                .map(result -> new Draft(
                    ensureCitation(result.reply(), hits),
                    "openai_rag_assist_not_measured_accuracy",
                    result.operatorChecklist()))
                .orElseGet(() -> new Draft(
                    draftTemplate(intent, hits),
                    "openai_failed_" + retrievalNote,
                    List.of()));
        }
        return new Draft(
            draftTemplate(intent, hits),
            retrievalNote,
            List.of());
    }

    private static String ensureCitation(String reply, List<Hit> hits) {
        String cited = hits.stream()
            .map(hit -> "#" + hit.chunkId())
            .collect(java.util.stream.Collectors.joining(" / "));
        if (reply.contains("出典")) {
            return reply;
        }
        return "出典 " + cited + "\n" + reply;
    }

    private static final String NO_RAG_BANNER = "出典 該当なし（AI下書き・未確認）";

    private static String ensureNoRagBanner(String reply) {
        if (reply.contains("出典 該当なし")) {
            return reply;
        }
        return NO_RAG_BANNER + "\n" + reply;
    }

    @Transactional(readOnly = true)
    public List<InquiryRow> listInquiries(int limit) {
        int cap = Math.min(Math.max(limit, 1), 100);
        return jdbc.query("""
            select id, transcript, intent, reply, sources::text as sources, created_at
              from inbound_inquiries
             order by created_at desc
             limit ?
            """,
            (rs, i) -> new InquiryRow(
                rs.getObject("id", UUID.class),
                rs.getString("transcript"),
                rs.getString("intent"),
                rs.getString("reply"),
                rs.getString("sources"),
                rs.getTimestamp("created_at").toInstant().toString()),
            cap);
    }

    @Transactional
    public boolean deleteInquiry(AuthUser user, UUID id) {
        int n = jdbc.update("delete from inbound_inquiries where id = ?", id);
        if (n > 0) {
            audit.record(user, "call_center.inquiry_deleted", "inbound_inquiry", id);
        }
        return n > 0;
    }

    @Transactional(readOnly = true)
    public List<ArticleRow> listKnowledge() {
        return jdbc.query("""
            select id, title, body, intent
              from knowledge_articles
             order by intent, title
            """,
            (rs, i) -> new ArticleRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("intent")));
    }

    public record DemoSeedResult(int knowledgeArticlesAdded, int demoInquiriesAdded) {
    }

    private static final List<String> DEMO_INQUIRIES = List.of(
        "請求書が届きません。再発行できますか。",
        "発送から1週間経ちますが、商品がまだ届きません。",
        "ログインできません。パスワードを再設定したいです。"
    );

    @Transactional
    public int seedKnowledge(UUID tenantId) {
        return KnowledgeCatalog.seedIfEmpty(jdbc, tenantId);
    }

    /**
     * コールセンター画面の確認用。FAQ と受付履歴のサンプル。発信はしない。
     */
    @Transactional
    public DemoSeedResult seedDemoIfEmpty(AuthUser user) {
        int articles = KnowledgeCatalog.seedIfEmpty(jdbc, user.tenantId());
        Integer existing = jdbc.queryForObject(
            "select count(*) from inbound_inquiries",
            Integer.class);
        int inquiriesAdded = 0;
        if (existing == null || existing == 0) {
            for (String text : DEMO_INQUIRIES) {
                assist(user, text, null);
                inquiriesAdded++;
            }
        }
        return new DemoSeedResult(articles, inquiriesAdded);
    }

    private List<Hit> search(String query, String intent) {
        Set<String> qTokens = LexicalTokens.of(query);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select id, article_id, content, intent from knowledge_chunks
            """);
        List<Hit> ranked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String content = String.valueOf(row.get("content"));
            Set<String> shared = new HashSet<>(qTokens);
            shared.retainAll(LexicalTokens.of(content));
            Set<String> strong = new HashSet<>();
            for (String tok : shared) {
                if (tok.length() >= 3 || tok.chars().allMatch(c -> c < 128)
                    || IntentClassifier.KEYWORD_TOKENS.contains(tok)) {
                    strong.add(tok);
                }
            }
            if (strong.isEmpty()) {
                continue;
            }
            String rowIntent = String.valueOf(row.get("intent"));
            int bonus = intent != null && intent.equals(rowIntent) ? 2 : 0;
            int score = strong.size() * 3 + (shared.size() - strong.size()) + bonus;
            ranked.add(new Hit(
                (UUID) row.get("id"),
                (UUID) row.get("article_id"),
                content,
                rowIntent,
                score));
        }
        ranked.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(Hit::chunkId));
        return ranked.size() > 3 ? ranked.subList(0, 3) : ranked;
    }

    private static String draftTemplate(String intent, List<Hit> hits) {
        if (hits.isEmpty()) {
            return "[回答案] 意図は " + intent + " ですが、ナレッジに該当がありません。"
                + " 出典の無い案内は作りません。";
        }
        String cited = String.join(" / ", hits.stream().map(hit -> "#" + hit.chunkId()).toList());
        return "[回答案] 意図 " + intent + "。出典 " + cited + "。" + hits.get(0).content()
            + " これは生成だけで作ったFAQではありません。";
    }
}
