package com.kadensaas.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.kadensaas.security.AuthUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 動作確認用のサンプルデータを、いまのテナントに投入する。
 *
 * <p>★ 「正常系だけ」のデータを作らない。DNC で止まる相手、無効な番号、
 * 留守番電話、断られた通話を必ず混ぜる。きれいなデータしか無いと、
 * 関門が効いているのか・KPI の分母が正しいのかを画面から確認できない。
 * 止まるところまで含めて初めてデモになる。
 *
 * <p>★ 過去 14 日分の通話履歴を作る。当日分だけだと KPI の日次推移と
 * 時間帯別の分析が空になり、ダッシュボードが何も語らない。
 *
 * <p>★ テナントをまたがない。{@code @Transactional} の中で実行するので
 * RLS が効き、いまのテナント以外には 1 行も入らない。
 *
 * <p>★ 冪等ではない。呼ぶたびに増える。既にデータがあるかは
 * 呼び出し側（コントローラ）が確認する。
 */
@Service
public class SampleDataService {

    /** 投入結果。画面にそのまま出せる形にする。 */
    public record Summary(int customers, int campaigns, int callTargets,
                          int callSessions, int dncEntries, int callbacks) {
    }

    private static final String[][] COMPANIES = {
        {"株式会社アルファ商事", "田中", "料金面に関心あり。次回は他社比較を提示する"},
        {"ベータ工業株式会社", "佐藤", null},
        {"ガンマシステムズ", "鈴木", "担当者が変わったばかり"},
        {"デルタ物流株式会社", "高橋", null},
        {"イプシロン食品", "伊藤", "受付で止まることが多い"},
        {"ゼータ建設株式会社", "渡辺", null},
        {"イータ製薬", "山本", "6月に一度断られている"},
        {"シータ電機株式会社", "中村", null},
        {"イオタ不動産", "小林", null},
        {"カッパ運輸株式会社", "加藤", "夕方以降のほうが繋がりやすい"},
    };

    // ★ 実在しない番号帯を使う。デモデータから本当に発信してしまう事故を防ぐ。
    //   03-1234-56xx は日本国内で割り当てのない形
    private static final String PHONE_PREFIX = "+81312345";

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SampleDataService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** すでにサンプル（というより何らかの顧客）が入っているか。 */
    @Transactional(readOnly = true)
    public boolean hasData() {
        Integer n = jdbc.queryForObject("select count(*) from customers", Integer.class);
        return n != null && n > 0;
    }

    @Transactional
    public Summary generate(AuthUser user) {
        UUID tenantId = user.tenantId();

        // ★ 発信者番号。無いと発信要求が落ちるので、一緒に用意しておく。
        //   実在しない番号なので、そのまま本番の発信には使えない
        jdbc.update("""
            insert into tenant_telephony (tenant_id, caller_id, recording_enabled, dialing_enabled)
            values (?, ?, true, true)
            on conflict (tenant_id) do nothing
            """, tenantId, "+81300000000");

        UUID campaignId = UUID.randomUUID();
        jdbc.update("""
            insert into campaigns (id, tenant_id, name, status, script)
            values (?, ?, ?, 'running', ?)
            """, campaignId, tenantId, "サンプル: 新規開拓キャンペーン",
            "お世話になっております。○○社の△△と申します。\n"
            + "本日は通信費の見直しについてご案内のお電話です。");

        int customers = 0, targets = 0, sessions = 0, dnc = 0, callbacks = 0;

        for (int i = 0; i < COMPANIES.length; i++) {
            String[] c = COMPANIES[i];
            String e164 = PHONE_PREFIX + String.format("%03d", 100 + i);
            String raw = "03-1234-5" + String.format("%03d", 100 + i);

            UUID customerId = UUID.randomUUID();
            jdbc.update("""
                insert into customers (id, tenant_id, company_name, contact_name, status, owner_id, note)
                values (?, ?, ?, ?, 'new', ?, ?)
                """, customerId, tenantId, c[0], c[1], user.userId(), c[2]);
            customers++;

            UUID phoneId = UUID.randomUUID();
            jdbc.update("""
                insert into customer_phones (id, tenant_id, customer_id, raw_number, e164, kind, is_primary)
                values (?, ?, ?, ?, ?, 'main', true)
                """, phoneId, tenantId, customerId, raw, e164);

            // ★ 1 件は再勧誘拒否にする。関門が止めることを画面で確認できるように
            if (i == 4) {
                jdbc.update("""
                    insert into do_not_call_entries (tenant_id, e164, reason, source, created_by)
                    values (?, ?, ?, 'customer_request', ?)
                    on conflict (tenant_id, e164) do nothing
                    """, tenantId, e164, "電話口で再勧誘を断られた", user.userId());
                dnc++;
            }

            jdbc.update("""
                insert into call_targets (tenant_id, campaign_id, customer_id, phone_id, priority, state)
                values (?, ?, ?, ?, ?, 'pending')
                """, tenantId, campaignId, customerId, phoneId, 10 + i * 10);
            targets++;

            sessions += createHistory(tenantId, user, campaignId, customerId, e164, i);

            // 数件だけ再架電予定を入れる
            if (i % 4 == 1) {
                jdbc.update("""
                    insert into callbacks (tenant_id, customer_id, scheduled_at, reason, assigned_to, status)
                    values (?, ?, ?, ?, ?, 'open')
                    """, tenantId, customerId,
                    OffsetDateTime.now().plusDays(1 + i % 3),
                    "資料送付後に再連絡", user.userId());
                callbacks++;
            }
        }

        // ★ 監査は同じトランザクションの中で書く。別にすると
        //   「データは入ったが記録が無い」（またはその逆）が起きて、
        //   記録が事実と食い違う
        audit.record(user, "admin.sample_data_generated", "tenant", tenantId);

        return new Summary(customers, 1, targets, sessions, dnc, callbacks);
    }

    /**
     * 過去 14 日分の通話履歴。
     *
     * <p>★ 結果の分布を現実的にする。全部「接続」にすると接続率 100% になり、
     * KPI の画面が意味を持たない。実際の架電では 6〜7 割が不在・話し中で終わる。
     */
    private int createHistory(UUID tenantId, AuthUser user, UUID campaignId,
                              UUID customerId, String e164, int index) {
        var rnd = ThreadLocalRandom.current();
        int made = 0;

        int attempts = 1 + rnd.nextInt(4);
        for (int a = 0; a < attempts; a++) {
            int daysAgo = 1 + rnd.nextInt(14);
            int hour = 9 + rnd.nextInt(11);   // 9〜19 時
            OffsetDateTime startedAt = OffsetDateTime.now()
                .minusDays(daysAgo)
                .withHour(hour)
                .withMinute(rnd.nextInt(60))
                .withSecond(0).withNano(0);

            // ★ 一部は関門が止めた記録にする。ダッシュボードの
            //   「止めた理由」が空だと、その欄の意味が伝わらない
            if (index == 4 && a == 0) {
                jdbc.update("""
                    insert into call_sessions
                      (tenant_id, campaign_id, customer_id, operator_id, provider,
                       direction, from_e164, to_e164, dial_state, blocked_reason, started_at)
                    values (?, ?, ?, ?, 'twilio', 'outbound', ?, ?, 'blocked', ?, ?)
                    """, tenantId, campaignId, customerId, user.userId(),
                    "+81300000000", e164, "do_not_call", startedAt);
                made++;
                continue;
            }
            if (index == 7 && a == 0) {
                jdbc.update("""
                    insert into call_sessions
                      (tenant_id, campaign_id, customer_id, operator_id, provider,
                       direction, from_e164, to_e164, dial_state, blocked_reason, started_at)
                    values (?, ?, ?, ?, 'twilio', 'outbound', ?, ?, 'blocked', ?, ?)
                    """, tenantId, campaignId, customerId, user.userId(),
                    "+81300000000", e164, "outside_hours", startedAt);
                made++;
                continue;
            }

            // ★ 1 件だけアポイントを確定で作る。実際のアポ率は 1〜3% なので
            //   乱数任せだと 28 件程度の通話では 0 件になることが多く、
            //   ダッシュボードの「成果率」が常に 0% で意味を持たなくなる。
            //   サンプルなので、指標が読める状態を優先する
            Map<String, Object> outcome = (index == 0 && a == 0)
                ? Map.of("code", "APPOINTMENT", "dial_state", "completed", "duration", 320)
                : pickOutcome(rnd.nextInt(100));
            String code = (String) outcome.get("code");
            String dialState = (String) outcome.get("dial_state");
            Integer duration = (Integer) outcome.get("duration");

            UUID sessionId = UUID.randomUUID();
            jdbc.update("""
                insert into call_sessions
                  (id, tenant_id, campaign_id, customer_id, operator_id, provider,
                   provider_call_sid, direction, from_e164, to_e164,
                   dial_state, disposition_code, started_at, answered_at, ended_at, duration_seconds)
                values (?, ?, ?, ?, ?, 'twilio', ?, 'outbound', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId, tenantId, campaignId, customerId, user.userId(),
                "CAsample" + sessionId.toString().replace("-", "").substring(0, 24),
                "+81300000000", e164, dialState, code,
                startedAt,
                duration != null ? startedAt.plusSeconds(8) : null,
                duration != null ? startedAt.plusSeconds(8 + duration) : startedAt.plusSeconds(25),
                duration);

            // ★ 結果は履歴にも残す。call_sessions 側は最新値のキャッシュで、
            //   正はこちらという設計なので、片方だけ入れない
            jdbc.update("""
                insert into call_dispositions
                  (tenant_id, call_session_id, code, source, recorded_by, recorded_at)
                values (?, ?, ?, 'operator', ?, ?)
                """, tenantId, sessionId, code, user.userId(), startedAt);

            made++;
        }
        return made;
    }

    /**
     * 架電結果の分布。
     *
     * <p>実際の新規開拓に近い比率にしてある。接続率が高すぎると
     * 「この数字なら改善不要」と読めてしまい、画面の役に立たない。
     */
    private Map<String, Object> pickOutcome(int roll) {
        if (roll < 34) {
            return Map.of("code", "NO_ANSWER", "dial_state", "no_answer");
        }
        if (roll < 46) {
            return Map.of("code", "BUSY", "dial_state", "busy");
        }
        if (roll < 58) {
            return Map.of("code", "VOICEMAIL", "dial_state", "completed", "duration", 18);
        }
        if (roll < 74) {
            return Map.of("code", "GATEKEEPER", "dial_state", "completed", "duration", 42);
        }
        if (roll < 86) {
            return Map.of("code", "NOT_INTERESTED", "dial_state", "completed", "duration", 65);
        }
        if (roll < 95) {
            return Map.of("code", "CONNECTED", "dial_state", "completed", "duration", 180);
        }
        if (roll < 98) {
            return Map.of("code", "APPOINTMENT", "dial_state", "completed", "duration", 320);
        }
        return Map.of("code", "INVALID_NUMBER", "dial_state", "failed");
    }

    /** 投入したサンプルを消す。動作確認をやり直せるように。 */
    @Transactional
    public void clear(AuthUser user) {
        audit.record(user, "admin.sample_data_cleared", "tenant", user.tenantId());
        // ★ 順序に注意。外部キーの子から消す。
        //   call_sessions は customers に cascade で消えるが、
        //   キャンペーンだけが残ると画面に空のキャンペーンが並ぶ
        for (String sql : List.of(
                "delete from callbacks",
                "delete from call_targets",
                "delete from call_sessions",
                "delete from do_not_call_entries",
                "delete from customers",
                "delete from campaigns")) {
            jdbc.update(sql);
        }
    }
}
