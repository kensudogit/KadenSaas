package com.kadensaas.service;

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

        // ★ 停止スイッチは 2 段。全体（環境変数）とテナント別（DB）。
        //
        //   全体だけだと「1 社から苦情が来たので、その会社への発信だけ止める」
        //   ができず、全テナントを巻き添えにするか、何もしないかの二択になる。
        //   テナント別だけだと、基盤側の事故で全部止めたいときに
        //   テナントの数だけ操作することになる。両方要る。
        //
        //   テナント別は画面から即座に切り替えられる（デプロイを待たない）。
        if (!dialingEnabled) {
            return new Decision.Blocked("dialing_disabled",
                "発信が停止されています（システム全体）");
        }

        Boolean tenantDialing = jdbc.query(
            "select dialing_enabled from tenant_telephony where tenant_id = ?",
            rs -> rs.next() ? rs.getBoolean(1) : null, tenant.getId());

        // ★ 行が無い＝発信者番号が未設定。鳴らせないので止める。
        //   ここを「設定が無いから素通り」にすると、from が null のまま
        //   Twilio に渡って分かりにくいエラーになる
        if (tenantDialing == null) {
            return new Decision.Blocked("telephony_not_configured",
                "発信者番号が設定されていません。管理画面で設定してください");
        }
        if (!tenantDialing) {
            return new Decision.Blocked("dialing_disabled",
                "このテナントの発信が停止されています");
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
     * <p>★ 判定はすべて DB 側で、テナントのタイムゾーンで行う。
     * JVM のタイムゾーンを一切経由させないためである。
     *
     * <p>以前は Java 側で {@code ZonedDateTime.now(zone)} と
     * エンティティの {@code LocalTime} を比べていた。ところが
     * {@code hibernate.jdbc.time_zone: UTC} は {@code time} 型の列にも効くので、
     * 読み書きのたびに値が JVM のタイムゾーン分ずれる。往復では辻褄が合うため
     * 気付きにくいが、DB の中身は間違っており、SQL で直接読む診断画面とは
     * 9 時間ずれた時間帯が表示される。さらに、同じ判断を Java と SQL の
     * 2 箇所で書いていたため、両者が食い違う余地があった。
     * 判断は 1 つの式にまとめ、診断画面と同じものを使う。
     *
     * <p>★ 祝日の判定も同じ式の中でテナントの日付を使う。サーバーの日付で
     * 数えると、深夜帯に前日／翌日の祝日判定になる。
     */
    private Decision checkCallingWindow(Tenant tenant) {
        var w = jdbc.queryForMap("""
            select extract(isodow from now() at time zone timezone)::int
                     = any(calling_weekdays)                              as in_weekday,
                   (now() at time zone timezone)::time
                     >= calling_hours_start
                   and (now() at time zone timezone)::time
                     < calling_hours_end                                  as in_hours,
                   exclude_holidays
                   and exists (select 1 from public_holidays
                                where holiday_date
                                        = (now() at time zone timezone)::date) as on_holiday,
                   to_char(calling_hours_start, 'HH24:MI')                as starts,
                   to_char(calling_hours_end,   'HH24:MI')                as ends,
                   to_char(now() at time zone timezone, 'Dy')             as today
              from tenants where id = ?
            """, tenant.getId());

        if (!Boolean.TRUE.equals(w.get("in_weekday"))) {
            return new Decision.Blocked("outside_weekday",
                "このテナントの架電曜日ではありません（" + w.get("today") + "）");
        }

        if (Boolean.TRUE.equals(w.get("on_holiday"))) {
            return new Decision.Blocked("holiday", "祝日のため架電しません");
        }

        if (!Boolean.TRUE.equals(w.get("in_hours"))) {
            return new Decision.Blocked("outside_hours",
                "架電可能時間外です（" + w.get("starts") + "-" + w.get("ends")
                    + " " + tenant.getTimezone() + "）");
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

}
