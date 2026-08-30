package com.kadensaas.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.UUID;

import com.kadensaas.domain.Tenant;
import com.kadensaas.repository.CallSessionRepository;
import com.kadensaas.repository.DoNotCallRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 発信の関門。
 *
 * <p>★ このプロジェクトで「かけてよいか」を判断する場所はここだけ。
 * 発信経路が複数あると（画面から / 自動発信から / 再架電から）、
 * どれか 1 つが関門を通らないだけで、断った相手に再び電話がかかる。
 * それは謝って済む種類の不具合ではないので、判断を 1 箇所に集める。
 *
 * <p>★ 守れているかは grep 1 回で確認できるようにしてある。
 * {@code CallService} 以外がこのクラスを呼ばず、かつ voice サービスは
 * {@code queued} の call_session が既にある場合しか発信しない。
 * つまり「行が無ければ鳴らせない」ので、関門を迂回する経路が作れない。
 *
 * <p>★ ここで止めた発信も記録する（dial_state = blocked）。
 * 黙って握りつぶすと「なぜかけていないのか」を後から説明できず、
 * 監査でも運用でも困る。止めた事実と理由が残ることが重要。
 */
@Service
public class DialingGate {

    /** 判定の結果。理由は利用者にも監査にもそのまま出せる文言にする。 */
    public sealed interface Decision {
        record Allowed() implements Decision {
        }

        record Blocked(String reason, String detail) implements Decision {
        }
    }

    private final DoNotCallRepository dnc;
    private final CallSessionRepository calls;
    private final JdbcTemplate jdbc;
    private final boolean dialingEnabled;

    public DialingGate(DoNotCallRepository dnc,
                       CallSessionRepository calls,
                       JdbcTemplate jdbc,
                       @Value("${kaden.dialing.enabled:true}") boolean dialingEnabled) {
        this.dnc = dnc;
        this.calls = calls;
        this.jdbc = jdbc;
        this.dialingEnabled = dialingEnabled;
    }

    /**
     * 発信してよいかを判定する。
     *
     * <p>★ MANDATORY。トランザクションの外から呼ばれたら例外で落ちる。
     * この設計では、Spring のトランザクションの外で DB に触ると
     * app.tenant_id が設定されず、RLS が黙って 0 行を返す。
     * DNC の照合が 0 行になれば「拒否されていない」と判定してしまい、
     * 断った相手に電話がかかる。静かに間違えるより、落ちるほうがよい。
     *
     * @param tenant   架電元テナント（時間帯と上限回数を持つ）
     * @param e164     かける先。正規化済みであること
     * @param customerId 回数の集計に使う
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Decision evaluate(Tenant tenant, String e164, UUID customerId) {

        // ★ 障害時に発信だけを止められるようにしておく。アプリ全体を
        //   巻き戻さずに「今日はかけない」ができないと、事故のときに
        //   デプロイを待つことになる
        if (!dialingEnabled) {
            return new Decision.Blocked("dialing_disabled",
                "発信が管理者により停止されています");
        }

        // ★ 最初に DNC。ここを後ろに置くと、他の理由で弾かれたときに
        //   「拒否されていること」が記録に残らない
        if (dnc.existsByE164(e164)) {
            return new Decision.Blocked("do_not_call",
                "この番号は再勧誘拒否として登録されています");
        }

        Decision hours = checkCallingWindow(tenant);
        if (hours != null) {
            return hours;
        }

        Decision attempts = checkAttemptLimits(tenant, customerId);
        if (attempts != null) {
            return attempts;
        }

        // ★ 最後に二重発信。DB の部分ユニークインデックスが本当の砦だが、
        //   ここで先に見ておくと利用者に分かる文言を返せる
        if (calls.countInFlightTo(e164) > 0) {
            return new Decision.Blocked("already_in_flight",
                "この番号への通話がすでに進行中です");
        }

        return new Decision.Allowed();
    }

    /**
     * 架電可能な時間帯か。
     *
     * <p>★ サーバーのタイムゾーンではなくテナントのタイムゾーンで判定する。
     * UTC で動くコンテナに載せた瞬間に 9 時間ずれ、早朝に架電することになる。
     */
    private Decision checkCallingWindow(Tenant tenant) {
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(zone);

        int[] weekdays = tenant.getCallingWeekdays();
        DayOfWeek today = now.getDayOfWeek();
        // DB には ISO の 1=月曜 で入れてある
        boolean weekdayAllowed = weekdays != null
            && Arrays.stream(weekdays).anyMatch(d -> d == today.getValue());
        if (!weekdayAllowed) {
            return new Decision.Blocked("outside_weekday",
                "このテナントの架電曜日ではありません（" + today + "）");
        }

        if (tenant.isExcludeHolidays() && isJapaneseHoliday(now.toLocalDate())) {
            return new Decision.Blocked("holiday", "祝日のため架電しません");
        }

        LocalTime t = now.toLocalTime();
        LocalTime start = tenant.getCallingHoursStart();
        LocalTime end = tenant.getCallingHoursEnd();
        if (t.isBefore(start) || !t.isBefore(end)) {
            return new Decision.Blocked("outside_hours",
                "架電可能時間外です（" + start + "-" + end + " " + tenant.getTimezone() + "）");
        }

        return null;
    }

    /**
     * 架電回数の上限。
     *
     * <p>★ 「今日何回かけたか」はテナントのタイムゾーンでの日付で数える。
     * UTC の日付で数えると、日本時間の朝 9 時に前日分としてカウントされ、
     * 上限が実質 2 倍になる。
     */
    private Decision checkAttemptLimits(Tenant tenant, UUID customerId) {
        if (customerId == null) {
            return null;
        }

        Integer today = jdbc.queryForObject("""
            select count(*) from call_sessions
             where customer_id = ?
               and dial_state <> 'blocked'
               and (started_at at time zone ?)::date = (now() at time zone ?)::date
            """, Integer.class, customerId, tenant.getTimezone(), tenant.getTimezone());

        if (today != null && today >= tenant.getMaxAttemptsPerDay()) {
            return new Decision.Blocked("max_attempts_per_day",
                "本日の架電回数の上限（" + tenant.getMaxAttemptsPerDay() + " 回）に達しています");
        }

        Integer total = jdbc.queryForObject(
            "select count(*) from call_sessions where customer_id = ? and dial_state <> 'blocked'",
            Integer.class, customerId);

        if (total != null && total >= tenant.getMaxAttemptsTotal()) {
            return new Decision.Blocked("max_attempts_total",
                "この顧客への通算架電回数の上限（" + tenant.getMaxAttemptsTotal() + " 回）に達しています");
        }

        return null;
    }

    /**
     * 日本の祝日か。
     *
     * <p>★ 祝日表はライブラリに任せず DB に持つ。ライブラリだと
     * 法改正（五輪のときの移動など）に追随するためにデプロイが必要になる。
     * 運用で足せる形にしておく。表が空なら祝日なしとして扱う。
     */
    private boolean isJapaneseHoliday(LocalDate date) {
        Integer count = jdbc.queryForObject(
            "select count(*) from public_holidays where holiday_date = ?", Integer.class, date);
        return count != null && count > 0;
    }
}
