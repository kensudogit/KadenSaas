package com.kadensaas.service.callcenter;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * デモ用ナレッジ。生成だけで FAQ を捏造しないための種。
 */
final class KnowledgeCatalog {

    record Article(String title, String body, String intent) {
    }

    static final List<Article> SEED = List.of(
        new Article(
            "請求書の再発行",
            "請求書はマイページの請求履歴から再発行できます。invoice bill が届かない場合は登録メールアドレスを確認してください。",
            "billing"),
        new Article(
            "配送状況の確認",
            "発送後に追跡番号をメールします。発送から3営業日を過ぎても届かない場合は再送を手配します。",
            "shipping"),
        new Article(
            "パスワード再設定",
            "ログイン画面の password reset から再設定します。届かない場合は迷惑メールを確認してください。",
            "account"),
        new Article(
            "苦情受付",
            "苦情は受付番号を発行し、当日中に担当から連絡します。返金は調査後に判断します。",
            "complaint"),
        new Article(
            "営業時間",
            "サポートは平日 9:00-18:00 です。祝日は休みです。緊急の停止依頼はフォームから受けます。",
            "general")
    );

    private KnowledgeCatalog() {
    }

    static int seedIfEmpty(JdbcTemplate jdbc, UUID tenantId) {
        Integer n = jdbc.queryForObject(
            "select count(*) from knowledge_articles where tenant_id = ?",
            Integer.class,
            tenantId);
        if (n != null && n > 0) {
            return 0;
        }
        int added = 0;
        for (Article article : SEED) {
            UUID articleId = jdbc.queryForObject("""
                insert into knowledge_articles (tenant_id, title, body, intent)
                values (?, ?, ?, ?)
                returning id
                """, UUID.class, tenantId, article.title(), article.body(), article.intent());
            jdbc.update("""
                insert into knowledge_chunks (tenant_id, article_id, content, intent)
                values (?, ?, ?, ?)
                """, tenantId, articleId, article.title() + "。" + article.body(), article.intent());
            added++;
        }
        return added;
    }
}
