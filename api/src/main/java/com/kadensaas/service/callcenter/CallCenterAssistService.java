package com.kadensaas.service.callcenter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
 * <p>★ ヒットしたチャンクだけを引用する。無いときは「該当なし」。
 * 生成だけで FAQ を捏造しない。
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

    public CallCenterAssistService(
            JdbcTemplate jdbc,
            AuditService audit,
            CallCenterOpenAiClient openAi) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.openAi = openAi;
    }

    public boolean openAiConfigured() {
        return openAi.configured();
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

        IntentClassifier.Result nlp = IntentClassifier.classify(transcript);
        List<Hit> hits = search(transcript, nlp.intent());
        Draft draft = buildReply(transcript, nlp.intent(), hits);
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
            false,
            draft.note());
    }

    private record Draft(String reply, String note) {
    }

    private Draft buildReply(String transcript, String intent, List<Hit> hits) {
        if (hits.isEmpty()) {
            return new Draft(
                draftTemplate(intent, hits),
                "lexical_rag_no_hits");
        }
        if (openAi.configured()) {
            return openAi.compose(transcript, intent, hits)
                .map(text -> new Draft(
                    ensureCitation(text, hits),
                    "openai_rag_assist_not_measured_accuracy"))
                .orElseGet(() -> new Draft(
                    draftTemplate(intent, hits),
                    "openai_failed_lexical_fallback"));
        }
        return new Draft(
            draftTemplate(intent, hits),
            "lexical_rag_not_measured_accuracy");
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
