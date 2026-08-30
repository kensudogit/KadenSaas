package com.kadensaas.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.kadensaas.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * テナントの電話設定と、発信できるかどうかの診断。
 *
 * <p>★ 診断（{@link #diagnose}）がこのクラスの主目的。
 * 「なぜ鳴らないのか」を調べるのにログを掘らせない。架電 SaaS で
 * 発信できない原因は毎回同じ数種類（Twilio 未設定・発信者番号なし・
 * 停止スイッチ・時間帯・DNC）で、しかもそれぞれ別の場所に出る。
 * 1 箇所にまとめて「いま何が足りないか」を返す。
 *
 * <p>★ 診断は「できない理由」を具体的に返す。「発信できません」だけだと
 * 何を直せばよいか分からず、結局ログを読むことになる。
 */
@Service
public class TelephonySettingsService {

    /** 1 件の確認項目。画面にそのまま並べられる形にする。 */
    public record Check(String key, String label, boolean ok, String detail) {
    }

    public record Diagnosis(boolean canDial, List<Check> checks) {
    }

    private final JdbcTemplate jdbc;
    private final boolean globalDialingEnabled;
    private final String voiceBaseUrl;

    public TelephonySettingsService(
            JdbcTemplate jdbc,
            @Value("${kaden.dialing.enabled:true}") boolean globalDialingEnabled,
            @Value("${kaden.voice.base-url:}") String voiceBaseUrl) {
        this.jdbc = jdbc;
        this.globalDialingEnabled = globalDialingEnabled;
        this.voiceBaseUrl = voiceBaseUrl;
    }

    // ---------------------------------------------------------------- 設定

    @Transactional(readOnly = true)
    public Map<String, Object> get(AuthUser user) {
        var rows = jdbc.queryForList("""
            select caller_id, machine_detection, recording_enabled, dialing_enabled
              from tenant_telephony where tenant_id = ?
            """, user.tenantId());

        if (rows.isEmpty()) {
            // ★ null を返さない。画面が「未設定」を素直に描けるようにする
            return Map.of("configured", false);
        }
        var r = rows.get(0);
        return Map.of(
            "configured", true,
            "callerId", r.get("caller_id"),
            "machineDetection", r.get("machine_detection"),
            "recordingEnabled", r.get("recording_enabled"),
            "dialingEnabled", r.get("dialing_enabled"));
    }

    /**
     * 発信者番号などを保存する。
     *
     * <p>★ 発信者番号は E.164 に正規化してから入れる。DB の check 制約と
     * 同じ形をここでも見るのは、制約違反の生メッセージを利用者に出さないため。
     *
     * <p>★ 番号が Twilio で購入・検証済みかどうかは、ここでは確かめない。
     * 確かめるには Twilio の資格情報が要り、それを持つのは voice サービス。
     * 保存は通し、実際に使えるかは診断（{@link #diagnose}）で見る。
     */
    @Transactional
    public void save(AuthUser user, String rawCallerId, String machineDetection,
                     Boolean recordingEnabled, Boolean dialingEnabled) {

        String callerId = PhoneNumbers.toE164Jp(rawCallerId);

        String detection = machineDetection == null || machineDetection.isBlank()
            ? "DetectMessageEnd" : machineDetection.trim();
        if (!List.of("DetectMessageEnd", "Enable", "none").contains(detection)) {
            throw new IllegalArgumentException(
                "留守番電話の検出は DetectMessageEnd / Enable / none のいずれかです");
        }

        try {
            jdbc.update("""
                insert into tenant_telephony
                  (tenant_id, caller_id, machine_detection, recording_enabled, dialing_enabled)
                values (?, ?, ?, ?, ?)
                on conflict (tenant_id) do update set
                  caller_id = excluded.caller_id,
                  machine_detection = excluded.machine_detection,
                  recording_enabled = excluded.recording_enabled,
                  dialing_enabled = excluded.dialing_enabled,
                  updated_at = now()
                """,
                user.tenantId(), callerId, detection,
                recordingEnabled == null || recordingEnabled,
                dialingEnabled == null || dialingEnabled);
        } catch (DuplicateKeyException e) {
            // ★ 同じ発信者番号を 2 つのテナントで使わせない（tenant_telephony_caller_id_uniq）。
            //   使えると、A 社の苦情で止めた番号から B 社が発信し続けることになる
            throw new IllegalArgumentException(
                "この発信者番号は他のテナントで使用されています: " + callerId);
        }
    }

    /**
     * 発信の停止／再開だけを切り替える。
     *
     * <p>★ 設定全体の保存とは分ける。事故のときに押すスイッチなので、
     * 他の項目を巻き込まず、1 クリックで確実に止まるようにする。
     */
    @Transactional
    public void setDialingEnabled(AuthUser user, boolean enabled) {
        int updated = jdbc.update("""
            update tenant_telephony set dialing_enabled = ?, updated_at = now()
             where tenant_id = ?
            """, enabled, user.tenantId());
        if (updated == 0) {
            throw new IllegalStateException(
                "電話設定がまだありません。先に発信者番号を設定してください");
        }
    }

    // ---------------------------------------------------------------- 診断

    /**
     * いま発信できるか。できないなら何が足りないか。
     *
     * <p>★ 「発信できません」ではなく、項目ごとに ok / 理由を返す。
     * 架電が止まる原因は毎回同じ数種類だが、それぞれ別の場所に出るので、
     * 1 箇所に集めないと毎回ログを掘ることになる。
     */
    @Transactional(readOnly = true)
    public Diagnosis diagnose(AuthUser user) {
        List<Check> checks = new ArrayList<>();

        // 1. voice サービスの接続先
        checks.add(new Check("voice_base_url", "音声サービスの接続先",
            voiceBaseUrl != null && !voiceBaseUrl.isBlank(),
            voiceBaseUrl == null || voiceBaseUrl.isBlank()
                ? "VOICE_BASE_URL が未設定です"
                : voiceBaseUrl));

        // 2. 全体の停止スイッチ
        checks.add(new Check("global_dialing", "システム全体の発信",
            globalDialingEnabled,
            globalDialingEnabled ? "有効" : "停止中（環境変数 KADEN_DIALING_ENABLED）"));

        // 3. テナントの電話設定
        var rows = jdbc.queryForList("""
            select caller_id, dialing_enabled, recording_enabled
              from tenant_telephony where tenant_id = ?
            """, user.tenantId());

        if (rows.isEmpty()) {
            checks.add(new Check("caller_id", "発信者番号", false,
                "未設定です。管理画面で購入済み・検証済みの番号を登録してください"));
            checks.add(new Check("tenant_dialing", "このテナントの発信", false,
                "電話設定がありません"));
        } else {
            var r = rows.get(0);
            checks.add(new Check("caller_id", "発信者番号", true,
                String.valueOf(r.get("caller_id"))));
            boolean tenantDialing = Boolean.TRUE.equals(r.get("dialing_enabled"));
            checks.add(new Check("tenant_dialing", "このテナントの発信",
                tenantDialing,
                tenantDialing ? "有効" : "停止中（管理画面で再開できます）"));
        }

        // 4. 架電可能時間（テナントのタイムゾーンで判定）
        var window = jdbc.queryForMap("""
            select timezone,
                   (now() at time zone timezone)::time
                     between calling_hours_start and calling_hours_end as in_hours,
                   extract(isodow from now() at time zone timezone)::int = any(calling_weekdays)
                     as in_weekday,
                   to_char(calling_hours_start, 'HH24:MI') as starts,
                   to_char(calling_hours_end, 'HH24:MI') as ends
              from tenants where id = ?
            """, user.tenantId());

        boolean inHours = Boolean.TRUE.equals(window.get("in_hours"));
        boolean inWeekday = Boolean.TRUE.equals(window.get("in_weekday"));
        checks.add(new Check("calling_window", "架電可能な時間帯",
            inHours && inWeekday,
            (inWeekday ? "" : "本日は架電対象外の曜日です。")
                + (inHours ? "" : "架電可能時間外です。")
                + String.format("設定: %s-%s %s",
                    window.get("starts"), window.get("ends"), window.get("timezone"))));

        // 5. 架電できる相手がいるか
        Integer pending = jdbc.queryForObject(
            "select count(*) from call_targets where state = 'pending'", Integer.class);
        checks.add(new Check("queue", "架電待ちの相手",
            pending != null && pending > 0,
            pending == null || pending == 0
                ? "キューが空です。顧客と架電リストを登録してください"
                : pending + " 件"));

        // ★ 時間帯とキューは「今すぐ鳴らせるか」の条件だが、設定の不備ではない。
        //   canDial は「設定として発信可能か」を返し、時間帯は別に見せる
        boolean canDial = checks.stream()
            .filter(c -> List.of("voice_base_url", "global_dialing",
                                 "caller_id", "tenant_dialing").contains(c.key()))
            .allMatch(Check::ok);

        return new Diagnosis(canDial, checks);
    }
}
