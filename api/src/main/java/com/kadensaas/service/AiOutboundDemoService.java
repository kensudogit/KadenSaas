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
    public static final String AUTO_DIALER_CAMPAIGN_NAME = "サンプル: オートダイヤラー";
    public static final String IVR_CAMPAIGN_NAME = "サンプル: IVR 自動架電";

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
    private static final String AUTO_PHONE_PREFIX = "+813123453";
    private static final String IVR_PHONE_PREFIX = "+813123454";

    private static final String IVR_MENU_JSON = """
        {"prompt":"資料請求は1、担当者は2、不要は9です。","options":{
        "1":{"label":"資料請求","say":"資料請求を承りました。ありがとうございました。"},
        "2":{"label":"担当者","say":"担当者より折り返しご連絡いたします。"},
        "9":{"label":"不要","say":"承知いたしました。失礼いたします。"}}}
        """;

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

    @Transactional
    public SeedResult seedAutoDialerIfEmpty(AuthUser user) {
        UUID tenantId = user.tenantId();
        ensureTelephony(tenantId);
        UUID existing = findCampaignIdByName(tenantId, AUTO_DIALER_CAMPAIGN_NAME);
        if (existing != null) {
            return new SeedResult(buildOverview(tenantId, existing), false);
        }
        UUID campaignId = createDemoCampaign(
            tenantId, user, AUTO_DIALER_CAMPAIGN_NAME,
            "オートダイヤラーで順次発信するデモ用キャンペーンです。",
            false, true, false, null, null);
        seedTargets(tenantId, user, campaignId, AUTO_PHONE_PREFIX, new String[][] {
            {"株式会社プログレッシブ", "本田", null},
            {"シーケンス商事", "石井", "午前中がつながりやすい"},
            {"リレー工業", "岡田", null},
        });
        audit.record(user, "auto_dialer.demo_seeded", "campaign", campaignId);
        return new SeedResult(buildOverview(tenantId, campaignId), true);
    }

    @Transactional
    public SeedResult seedIvrIfEmpty(AuthUser user) {
        UUID tenantId = user.tenantId();
        ensureTelephony(tenantId);
        UUID existing = findCampaignIdByName(tenantId, IVR_CAMPAIGN_NAME);
        if (existing != null) {
            return new SeedResult(buildOverview(tenantId, existing), false);
        }
        UUID campaignId = createDemoCampaign(
            tenantId, user, IVR_CAMPAIGN_NAME,
            "IVR（音声ガイダンス）で相手に選択肢を提示するデモです。",
            false, false, true,
            "お世話になっております。KadenSaas デモセンターです。",
            IVR_MENU_JSON);
        seedTargets(tenantId, user, campaignId, IVR_PHONE_PREFIX, new String[][] {
            {"株式会社ボイスゲート", "森", null},
            {"テレフォンサービス", "原田", null},
            {"エコー商事", "柴田", "IVR テスト用"},
        });
        audit.record(user, "ivr_outbound.demo_seeded", "campaign", campaignId);
        return new SeedResult(buildOverview(tenantId, campaignId), true);
    }

    @Transactional(readOnly = true)
    public Overview ivrOverview(AuthUser user) {
        UUID id = findCampaignIdByName(user.tenantId(), IVR_CAMPAIGN_NAME);
        if (id == null) {
            return new Overview(null, null, null, List.of(), 0);
        }
        return buildOverview(user.tenantId(), id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAutoDialerCampaigns(AuthUser user) {
        return jdbc.queryForList("""
            select id, name, auto_dialer_interval_seconds as interval_seconds,
                   (select count(*) from call_targets t
                     where t.campaign_id = c.id and t.state = 'pending') as pending_count
              from campaigns c
             where c.tenant_id = ? and c.auto_dialer_enabled = true
             order by c.name
            """, user.tenantId());
    }

    private void ensureTelephony(UUID tenantId) {
        jdbc.update("""
            insert into tenant_telephony (tenant_id, caller_id, recording_enabled, dialing_enabled)
            values (?, ?, true, true)
            on conflict (tenant_id) do nothing
            """, tenantId, "+81300000000");
    }

    private UUID createDemoCampaign(UUID tenantId, AuthUser user, String name, String script,
                                    boolean aiEnabled, boolean autoDialer, boolean ivr,
                                    String ivrWelcome, String ivrMenuJson) {
        UUID campaignId = UUID.randomUUID();
        jdbc.update("""
            insert into campaigns
              (id, tenant_id, name, status, script, ai_agent_enabled,
               auto_dialer_enabled, auto_dialer_interval_seconds,
               ivr_enabled, ivr_welcome_text, ivr_menu_json)
            values (?, ?, ?, 'running', ?, ?, ?, 30, ?, ?, ?::jsonb)
            """,
            campaignId, tenantId, name, script, aiEnabled, autoDialer, ivr,
            ivrWelcome, ivrMenuJson);
        return campaignId;
    }

    private void seedTargets(UUID tenantId, AuthUser user, UUID campaignId,
                             String phonePrefix, String[][] companies) {
        for (int i = 0; i < companies.length; i++) {
            String[] c = companies[i];
            String e164 = phonePrefix + String.format("%02d", i);
            String raw = rawFromE164(e164);
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
            jdbc.update("""
                insert into call_targets (tenant_id, campaign_id, customer_id, phone_id, priority, state)
                values (?, ?, ?, ?, ?, 'pending')
                """, tenantId, campaignId, customerId, phoneId, 90 - i * 5);
        }
    }

    private UUID findCampaignIdByName(UUID tenantId, String name) {
        return findDemoCampaignId(tenantId, name);
    }

    private UUID findDemoCampaignId(UUID tenantId) {
        return findDemoCampaignId(tenantId, DEMO_CAMPAIGN_NAME);
    }

    private UUID findDemoCampaignId(UUID tenantId, String name) {
        List<UUID> ids = jdbc.query("""
                select id from campaigns
                 where tenant_id = ? and name = ?
                 limit 1
                """,
            (rs, rowNum) -> (UUID) rs.getObject("id"),
            tenantId, name);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static String rawFromE164(String e164) {
        if (e164.startsWith("+81") && e164.length() >= 12) {
            return "0" + e164.charAt(3) + "-"
                + e164.substring(4, 8) + "-"
                + e164.substring(8);
        }
        return e164;
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
