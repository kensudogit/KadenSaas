package com.kadensaas.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.security.AuthUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 自動架電画面用のサンプルキャンペーンとキュー一覧。
 *
 * <p>★ 実在しない 03-1234-52xx を使う（{@link SampleDataService} と帯域を分ける）。
 * ★ 冪等。同じ名前のキャンペーンがあれば作らず、待ち行列だけ返す。
 */
@Service
public class AiOutboundDemoService {

    static final String DEMO_CAMPAIGN_NAME = "サンプル: AI 自動架電";

    private static final String AI_SCRIPT = """
        架電SaaS「KadenSaas」の AI デモ担当として電話しています。
        まずはご担当者様につながるか確認し、興味があれば資料送付と
        15 分のオンライン説明を提案してください。断られたら丁寧に終話する。
        """;

    private static final String[][] AI_COMPANIES = {
        {"株式会社フューチャーリンク", "青木", "来月予算検討の予定あり"},
        {"ミライテック株式会社", "西村", null},
        {"サンライズ商事", "藤田", "受付で止まりやすい"},
        {"オーシャン物流", "松本", null},
        {"パシフィック食品", "村上", "夕方以降がつながりやすい"},
    };

    private static final String PHONE_PREFIX = "+813123452";

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AiOutboundDemoService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record TargetRow(
        UUID targetId,
        UUID phoneId,
        String companyName,
        String contactName,
        String e164,
        String state,
        boolean doNotCall,
        String note) {
    }

    public record Overview(
        UUID campaignId,
        String campaignName,
        String script,
        List<TargetRow> targets,
        int pendingCount) {
    }

    public record SeedResult(Overview overview, boolean created) {
    }

    @Transactional(readOnly = true)
    public Overview overview(AuthUser user) {
        UUID tenantId = user.tenantId();
        UUID campaignId = findDemoCampaignId(tenantId);
        if (campaignId == null) {
            return new Overview(null, null, null, List.of(), 0);
        }
        return buildOverview(tenantId, campaignId);
    }

    @Transactional
    public SeedResult seedIfEmpty(AuthUser user) {
        UUID tenantId = user.tenantId();

        jdbc.update("""
            insert into tenant_telephony (tenant_id, caller_id, recording_enabled, dialing_enabled)
            values (?, ?, true, true)
            on conflict (tenant_id) do nothing
            """, tenantId, "+81300000000");

        UUID existing = findDemoCampaignId(tenantId);
        if (existing != null) {
            return new SeedResult(buildOverview(tenantId, existing), false);
        }

        UUID campaignId = UUID.randomUUID();
        jdbc.update("""
            insert into campaigns (id, tenant_id, name, status, script, ai_agent_enabled)
            values (?, ?, ?, 'running', ?, true)
            """, campaignId, tenantId, DEMO_CAMPAIGN_NAME, AI_SCRIPT.trim());

        for (int i = 0; i < AI_COMPANIES.length; i++) {
            String[] c = AI_COMPANIES[i];
            String e164 = PHONE_PREFIX + String.format("%02d", i);
            String raw = "03-1234-52" + String.format("%02d", i);

            UUID customerId = UUID.randomUUID();
            jdbc.update("""
                insert into customers (id, tenant_id, company_name, contact_name, status, owner_id, note)
                values (?, ?, ?, ?, 'new', ?, ?)
                """, customerId, tenantId, c[0], c[1], user.userId(), c[2]);

            UUID phoneId = UUID.randomUUID();
            jdbc.update("""
                insert into customer_phones (id, tenant_id, customer_id, raw_number, e164, kind, is_primary)
                values (?, ?, ?, ?, ?, 'main', true)
                """, phoneId, tenantId, customerId, raw, e164);

            if (i == 2) {
                jdbc.update("""
                    insert into do_not_call_entries (tenant_id, e164, reason, source, created_by)
                    values (?, ?, ?, 'customer_request', ?)
                    on conflict (tenant_id, e164) do nothing
                    """, tenantId, e164, "デモ: 再勧誘拒否の例", user.userId());
            }

            jdbc.update("""
                insert into call_targets (tenant_id, campaign_id, customer_id, phone_id, priority, state)
                values (?, ?, ?, ?, ?, 'pending')
                """, tenantId, campaignId, customerId, phoneId, 100 - i * 5);
        }

        audit.record(user, "ai_outbound.demo_seeded", "campaign", campaignId);
        return new SeedResult(buildOverview(tenantId, campaignId), true);
    }

    private UUID findDemoCampaignId(UUID tenantId) {
        List<UUID> ids = jdbc.query("""
                select id from campaigns
                 where tenant_id = ? and name = ?
                 limit 1
                """,
            (rs, rowNum) -> (UUID) rs.getObject("id"),
            tenantId, DEMO_CAMPAIGN_NAME);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Overview buildOverview(UUID tenantId, UUID campaignId) {
        Map<String, Object> camp = jdbc.queryForMap("""
            select id, name, script from campaigns
             where tenant_id = ? and id = ?
            """, tenantId, campaignId);

        List<TargetRow> targets = jdbc.query("""
            select t.id as target_id,
                   t.phone_id,
                   c.company_name,
                   c.contact_name,
                   p.e164,
                   t.state,
                   c.note,
                   exists(select 1 from do_not_call_entries d
                           where d.tenant_id = t.tenant_id and d.e164 = p.e164) as is_dnc
              from call_targets t
              join customers c on c.id = t.customer_id
              join customer_phones p on p.id = t.phone_id
             where t.tenant_id = ? and t.campaign_id = ?
             order by t.priority desc, t.id
            """,
            (rs, rowNum) -> new TargetRow(
                (UUID) rs.getObject("target_id"),
                (UUID) rs.getObject("phone_id"),
                rs.getString("company_name"),
                rs.getString("contact_name"),
                rs.getString("e164"),
                rs.getString("state"),
                rs.getBoolean("is_dnc"),
                rs.getString("note")),
            tenantId, campaignId);

        long pending = targets.stream().filter(t -> "pending".equals(t.state())).count();
        return new Overview(
            campaignId,
            (String) camp.get("name"),
            (String) camp.get("script"),
            targets,
            (int) pending);
    }
}
