package com.kadensaas;

import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.service.CallService;
import com.kadensaas.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 発信の関門。
 *
 * <p>★ ここが通らなくなるのは「断った相手に電話がかかる」ということで、
 * 謝って済む種類の不具合ではない。だから条件ごとに 1 件ずつ固定する。
 *
 * <p>★ 止めたことが記録に残ることも確かめる。握りつぶすと
 * 「なぜかけなかったのか」を後から説明できず、監査でも運用でも困る。
 */
class DialingGateTest extends AbstractIntegrationTest {

    @Autowired
    CallService callService;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private record Fixture(UUID tenantId, AuthUser user, UUID customerId, UUID phoneId) {
    }

    private Fixture setup(String e164) {
        UUID tenantId = createTenant("gate-test", "関門テスト社");
        UUID userId = createUser(tenantId, "op@example.com", "operator");
        UUID customerId = createCustomer(tenantId, "対象会社");
        UUID phoneId = createPhone(tenantId, customerId, e164);
        configureTelephony(tenantId, "+81300000000", true);

        var user = new AuthUser(userId, tenantId, "op@example.com",
            AuthUser.Role.OPERATOR);
        TenantContext.set(tenantId);
        return new Fixture(tenantId, user, customerId, phoneId);
    }

    @Test
    @DisplayName("条件が揃っていれば発信できる")
    void allowsWhenEverythingIsFine() {
        var f = setup("+81312340001");

        var result = callService.requestDial(f.user(), f.phoneId(), null, null);

        assertThat(result.accepted())
            .as("blocked=%s / %s", result.blockedReason(), result.message())
            .isTrue();
        assertThat(result.callSessionId()).isNotNull();
    }

    @Test
    @DisplayName("★ 再勧誘拒否の相手には発信しない")
    void blocksDoNotCall() {
        var f = setup("+81312340002");
        admin().update("""
            insert into do_not_call_entries (tenant_id, e164, reason, source)
            values (?, '+81312340002', '電話口で断られた', 'customer_request')
            """, f.tenantId());

        var result = callService.requestDial(f.user(), f.phoneId(), null, null);

        assertThat(result.accepted())
            .as("DNC の相手に発信できたら、この製品は使えない")
            .isFalse();
        assertThat(result.blockedReason()).isEqualTo("do_not_call");
    }

    @Test
    @DisplayName("★ 止めた発信も理由つきで記録される")
    void recordsBlockedAttempts() {
        var f = setup("+81312340003");
        admin().update("""
            insert into do_not_call_entries (tenant_id, e164, reason, source)
            values (?, '+81312340003', '拒否', 'customer_request')
            """, f.tenantId());

        callService.requestDial(f.user(), f.phoneId(), null, null);

        var row = admin().queryForMap("""
            select dial_state, blocked_reason from call_sessions
             where tenant_id = ? and to_e164 = '+81312340003'
            """, f.tenantId());

        assertThat(row.get("dial_state")).isEqualTo("blocked");
        assertThat(row.get("blocked_reason"))
            .as("理由の無い blocked は、後から説明できない")
            .isEqualTo("do_not_call");
    }

    @Test
    @DisplayName("★ 架電可能時間外は発信しない")
    void blocksOutsideCallingHours() {
        var f = setup("+81312340004");
        // 深夜 0 時台だけを許可し、いまが対象外になるようにする
        admin().update("""
            update tenants set calling_hours_start = '03:00', calling_hours_end = '03:01'
             where id = ?
            """, f.tenantId());

        var result = callService.requestDial(f.user(), f.phoneId(), null, null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.blockedReason()).isEqualTo("outside_hours");
    }

    @Test
    @DisplayName("★ 架電対象外の曜日は発信しない")
    void blocksOutsideWeekday() {
        var f = setup("+81312340005");
        // 今日以外の曜日だけを許可する
        int todayIso = java.time.ZonedDateTime
            .now(java.time.ZoneId.of("Asia/Tokyo")).getDayOfWeek().getValue();
        int other = todayIso == 1 ? 2 : 1;
        admin().update("update tenants set calling_weekdays = ? where id = ?",
            new int[] {other}, f.tenantId());

        var result = callService.requestDial(f.user(), f.phoneId(), null, null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.blockedReason()).isEqualTo("outside_weekday");
    }

    @Test
    @DisplayName("★ 同じ番号への二重発信を止める")
    void blocksDoubleDial() {
        var f = setup("+81312340006");

        var first = callService.requestDial(f.user(), f.phoneId(), null, null);
        assertThat(first.accepted()).isTrue();

        var second = callService.requestDial(f.user(), f.phoneId(), null, null);

        assertThat(second.accepted())
            .as("同じ相手に 2 本同時にかかると、相手には迷惑電話に見える")
            .isFalse();
        assertThat(second.blockedReason()).isEqualTo("already_in_flight");
    }

    @Test
    @DisplayName("通話が終われば同じ番号に再度かけられる")
    void allowsAfterCallCompleted() {
        var f = setup("+81312340007");
        callService.requestDial(f.user(), f.phoneId(), null, null);

        admin().update("""
            update call_sessions set dial_state = 'completed', ended_at = now()
             where tenant_id = ? and to_e164 = '+81312340007'
            """, f.tenantId());

        var again = callService.requestDial(f.user(), f.phoneId(), null, null);
        assertThat(again.accepted()).isTrue();
    }

    @Test
    @DisplayName("★ テナント別の停止スイッチが効く")
    void blocksWhenTenantDialingDisabled() {
        var f = setup("+81312340008");
        configureTelephony(f.tenantId(), "+81300000000", false);

        var result = callService.requestDial(f.user(), f.phoneId(), null, null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.blockedReason()).isEqualTo("dialing_disabled");
    }

    @Test
    @DisplayName("★ 発信者番号が未設定なら発信しない")
    void blocksWhenTelephonyNotConfigured() {
        var f = setup("+81312340009");
        admin().update("delete from tenant_telephony where tenant_id = ?", f.tenantId());

        var result = callService.requestDial(f.user(), f.phoneId(), null, null);

        assertThat(result.accepted())
            .as("設定が無いまま通すと、from が null のまま Twilio に渡る")
            .isFalse();
        assertThat(result.blockedReason()).isEqualTo("telephony_not_configured");
    }

    @Test
    @DisplayName("★ 架電結果に DO_NOT_CALL を選ぶと、その場で拒否リストに入る")
    void dispositionDoNotCallRegistersDnc() {
        var f = setup("+81312340010");
        var result = callService.requestDial(f.user(), f.phoneId(), null, null);

        callService.recordDisposition(f.user(), result.callSessionId(),
            "DO_NOT_CALL", "電話口で断られた");

        Integer count = admin().queryForObject("""
            select count(*) from do_not_call_entries
             where tenant_id = ? and e164 = '+81312340010'
            """, Integer.class, f.tenantId());

        assertThat(count)
            .as("結果は記録したが拒否リストに入っていない、という状態を作らない。"
                + "次のキャンペーンで同じ人にかかる")
            .isEqualTo(1);
    }
}
