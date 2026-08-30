package com.kadensaas.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.domain.CallSession;
import com.kadensaas.domain.CustomerPhone;
import com.kadensaas.domain.Tenant;
import com.kadensaas.repository.CallSessionRepository;
import com.kadensaas.repository.CustomerPhoneRepository;
import com.kadensaas.repository.DispositionCodeRepository;
import com.kadensaas.repository.DoNotCallRepository;
import com.kadensaas.repository.TenantRepository;
import com.kadensaas.security.AuthUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 発信と通話結果の登録。
 *
 * <p>★ 発信はここからしか始まらない。{@link DialingGate} を通り、
 * 通った場合だけ {@code call_sessions} に {@code queued} の行を作る。
 * voice サービス（FastAPI）は「既にある queued の行」しか発信できないので、
 * この関門を迂回する経路は構造的に存在しない。
 *
 * <p>★ 関門で止めた場合も行を作る（{@code dial_state = blocked}）。
 * 握りつぶすと「なぜかけなかったのか」を後から説明できない。
 * 止めた事実と理由が残ることが、監査でも運用でも効く。
 */
@Service
public class CallService {

    /** 発信要求の結果。呼び出し側は blocked を 200 で返す（エラーではない）。 */
    public record DialResult(UUID callSessionId, boolean accepted,
                             String blockedReason, String message) {
    }

    private final DialingGate gate;
    private final TenantRepository tenants;
    private final CustomerPhoneRepository phones;
    private final CallSessionRepository calls;
    private final DispositionCodeRepository dispositions;
    private final DoNotCallRepository dnc;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public CallService(DialingGate gate,
                       TenantRepository tenants,
                       CustomerPhoneRepository phones,
                       CallSessionRepository calls,
                       DispositionCodeRepository dispositions,
                       DoNotCallRepository dnc,
                       AuditService audit,
                       JdbcTemplate jdbc) {
        this.gate = gate;
        this.tenants = tenants;
        this.phones = phones;
        this.calls = calls;
        this.dispositions = dispositions;
        this.dnc = dnc;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    /**
     * 発信を要求する。
     *
     * <p>実際に Twilio を叩くのは voice サービス。ここは
     * 「かけてよいか」を決め、その記録を作るところまでを担う。
     */
    @Transactional
    public DialResult requestDial(AuthUser user, UUID phoneId, UUID campaignId, UUID callTargetId) {

        CustomerPhone phone = phones.findById(phoneId)
            .orElseThrow(() -> new IllegalArgumentException("電話番号が見つかりません"));

        Tenant tenant = tenants.findById(user.tenantId())
            .orElseThrow(() -> new IllegalStateException("テナントが見つかりません"));

        var decision = gate.evaluate(tenant, phone.getE164(), phone.getCustomerId());

        if (decision instanceof DialingGate.Decision.Blocked blocked) {
            // ★ 止めたことも記録する。dial_state = blocked は終端なので、
            //   二重発信の部分ユニークインデックスにも引っかからない
            CallSession row = newSession(user, phone, campaignId, callTargetId);
            row.setDialState("blocked");
            row.setBlockedReason(blocked.reason());
            calls.save(row);

            audit.record(user, "call.blocked", "call_session", row.getId(),
                Json.of("reason", blocked.reason()));

            return new DialResult(row.getId(), false, blocked.reason(), blocked.detail());
        }

        CallSession row = newSession(user, phone, campaignId, callTargetId);
        row.setDialState("queued");

        try {
            calls.save(row);
            // ★ flush して部分ユニークインデックスの判定をここで受ける。
            //   関門の in-flight チェックは競合に弱く、同時要求は DB でしか止まらない
            calls.flush();
        } catch (DataIntegrityViolationException e) {
            return new DialResult(null, false, "already_in_flight",
                "この番号への通話がすでに進行中です");
        }

        recordEvent(user.tenantId(), row.getId(), "api", "queued", null);
        audit.record(user, "call.requested", "call_session", row.getId());

        return new DialResult(row.getId(), true, null, null);
    }

    private CallSession newSession(AuthUser user, CustomerPhone phone,
                                   UUID campaignId, UUID callTargetId) {
        Tenant tenant = tenants.findById(user.tenantId()).orElseThrow();
        CallSession row = new CallSession();
        row.setTenantId(user.tenantId());
        row.setCampaignId(campaignId);
        row.setCallTargetId(callTargetId);
        row.setCustomerId(phone.getCustomerId());
        row.setOperatorId(user.userId());
        row.setProvider("twilio");
        row.setDirection("outbound");
        // ★ 発信者番号はテナント設定から取る。画面から渡させない。
        //   渡させると、他人名義の番号での発信を許すことになる
        row.setFromE164(callerIdFor(tenant));
        row.setToE164(phone.getE164());
        row.setStartedAt(OffsetDateTime.now());
        return row;
    }

    /**
     * 発信者番号。設定が無ければ null を返す。
     *
     * <p>★ 行が無いときに例外を投げてはいけない。関門が
     * {@code telephony_not_configured} で止めた場合も、止めた記録を残すために
     * ここを通る。例外にすると「設定が無いテナントでは、止めたことすら
     * 記録できず 500 になる」ことになり、いちばん記録が要る場面で落ちる。
     */
    private String callerIdFor(Tenant tenant) {
        return jdbc.query(
            "select caller_id from tenant_telephony where tenant_id = ?",
            rs -> rs.next() ? rs.getString(1) : null, tenant.getId());
    }

    /**
     * オペレーターが架電結果を登録する。
     *
     * <p>★ 履歴に追記し、最新値を call_sessions にキャッシュする。
     * 上書きだけにすると「誰がいつ何に変えたか」が消え、
     * KPI の数字を後から説明できなくなる。
     *
     * <p>★ DO_NOT_CALL を選んだら、その場で DNC に登録する。
     * 「結果は記録したが拒否リストには入っていない」という状態を作らない。
     * ここが分かれていると、次のキャンペーンで同じ人にかかる。
     */
    @Transactional
    public void recordDisposition(AuthUser user, UUID callSessionId, String code, String note) {

        var dc = dispositions.findById(code)
            .orElseThrow(() -> new IllegalArgumentException("不明な結果コードです: " + code));

        CallSession call = calls.findById(callSessionId)
            .orElseThrow(() -> new IllegalArgumentException("通話が見つかりません"));

        jdbc.update("""
            insert into call_dispositions
              (tenant_id, call_session_id, code, note, source, recorded_by)
            values (?, ?, ?, ?, 'operator', ?)
            """, user.tenantId(), callSessionId, code, note, user.userId());

        call.setDispositionCode(code);
        calls.save(call);

        if (dc.isDnc()) {
            registerDnc(user, call.getToE164(), "架電結果 " + code + " により登録");
        }

        audit.record(user, "call.disposition", "call_session", callSessionId,
            Json.of("code", code));
    }

    /**
     * DNC への登録。
     *
     * <p>★ 番号に紐づける。顧客レコードを統合・分割しても拒否の意思は残る。
     * 既に入っていれば何もしない（冪等）。
     */
    @Transactional
    public void registerDnc(AuthUser user, String e164, String reason) {
        if (dnc.existsByE164(e164)) {
            return;
        }
        jdbc.update("""
            insert into do_not_call_entries (tenant_id, e164, reason, source, created_by)
            values (?, ?, ?, 'customer_request', ?)
            on conflict (tenant_id, e164) do nothing
            """, user.tenantId(), e164, reason, user.userId());

        audit.record(user, "dnc.registered", "do_not_call", null);
    }

    private void recordEvent(UUID tenantId, UUID callSessionId, String source,
                             String dialState, Map<String, Object> payload) {
        jdbc.update("""
            insert into call_events (tenant_id, call_session_id, source, dial_state, applied)
            values (?, ?, ?, ?, true)
            """, tenantId, callSessionId, source, dialState);
    }
}
