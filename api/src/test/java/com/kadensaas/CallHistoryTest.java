package com.kadensaas;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.security.JwtService;
import com.kadensaas.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 架電履歴。
 *
 * <p>★ いちばん重要なのは「オペレーターに他人の通話を見せない」こと。
 * 画面で出し分けるだけでは、API を直接叩けば読める。サーバー側で
 * 絞れていることを、実際に HTTP で叩いて確かめる。
 *
 * <p>★ 止めた発信（blocked）が履歴に出ることも確かめる。
 * 出ないと「なぜ架電数が伸びないのか」が画面から分からなくなる。
 */
@AutoConfigureMockMvc
class CallHistoryTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private record Setup(UUID tenantId, UUID alice, UUID bob,
                         String aliceToken, String bobToken, String managerToken) {
    }

    private Setup setup() {
        UUID tenantId = createTenant("hist", "履歴テスト社");
        UUID alice = createUser(tenantId, "alice@example.com", "operator");
        UUID bob = createUser(tenantId, "bob@example.com", "operator");
        UUID manager = createUser(tenantId, "mgr@example.com", "manager");

        UUID customer = createCustomer(tenantId, "対象会社");
        createPhone(tenantId, customer, "+81312349001");

        // それぞれ 1 本ずつ通話を作る
        insertCall(tenantId, customer, alice, "+81312349001", "completed", null);
        insertCall(tenantId, customer, bob, "+81312349002", "completed", null);
        // 止めた発信も 1 本
        insertCall(tenantId, customer, alice, "+81312349003", "blocked", "do_not_call");

        return new Setup(tenantId, alice, bob,
            token(tenantId, alice, "alice@example.com", AuthUser.Role.OPERATOR),
            token(tenantId, bob, "bob@example.com", AuthUser.Role.OPERATOR),
            token(tenantId, manager, "mgr@example.com", AuthUser.Role.MANAGER));
    }

    private String token(UUID tenantId, UUID userId, String email, AuthUser.Role role) {
        return jwt.issue(new AuthUser(userId, tenantId, email, role));
    }

    private void insertCall(UUID tenantId, UUID customerId, UUID operatorId,
                            String to, String state, String blockedReason) {
        admin().update("""
            insert into call_sessions
              (tenant_id, customer_id, operator_id, from_e164, to_e164,
               dial_state, blocked_reason, started_at, duration_seconds)
            values (?, ?, ?, '+81300000000', ?, ?, ?, now(), 60)
            """, tenantId, customerId, operatorId, to, state, blockedReason);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(String token, String query) throws Exception {
        String body = mvc.perform(get("/api/v1/call-history" + query)
                .header("Authorization", "Bearer " + token))
            .andReturn().getResponse().getContentAsString();
        return json.readValue(body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("rows");
    }

    @Test
    @DisplayName("★ オペレーターには他人の通話が見えない")
    void operatorSeesOnlyOwnCalls() throws Exception {
        var s = setup();

        var aliceRows = rows(fetch(s.aliceToken(), ""));
        assertThat(aliceRows)
            .as("自分の通話（発信 1 本と止めた 1 本）だけが見えるはず")
            .hasSize(2);
        assertThat(aliceRows)
            .allSatisfy(r -> assertThat(r.get("operator_id"))
                .as("画面ではなくサーバーで絞れていないと、API を直接叩けば読める")
                .isEqualTo(s.alice().toString()));

        var bobRows = rows(fetch(s.bobToken(), ""));
        assertThat(bobRows).hasSize(1);
        assertThat(bobRows.get(0).get("operator_id")).isEqualTo(s.bob().toString());
    }

    @Test
    @DisplayName("★ オペレーターが他人の operatorId を指定しても、他人の通話は見えない")
    void operatorCannotOverrideScope() throws Exception {
        var s = setup();

        var rows = rows(fetch(s.aliceToken(), "?operatorId=" + s.bob()));

        assertThat(rows)
            .as("パラメータで他人を指定すると見えてしまう、という抜け道を作らない")
            .allSatisfy(r -> assertThat(r.get("operator_id"))
                .isEqualTo(s.alice().toString()));
    }

    @Test
    @DisplayName("マネージャーは全員分を見られる")
    void managerSeesEveryone() throws Exception {
        var s = setup();

        var page = fetch(s.managerToken(), "");
        assertThat(rows(page)).hasSize(3);
        assertThat(page.get("scopedToSelf")).isEqualTo(false);
    }

    @Test
    @DisplayName("★ 自分の分だけを見ていることが応答から分かる")
    void tellsTheOperatorTheListIsScoped() throws Exception {
        var s = setup();

        assertThat(fetch(s.aliceToken(), "").get("scopedToSelf"))
            .as("黙って絞ると「件数が合わない」という問い合わせになる")
            .isEqualTo(true);
    }

    @Test
    @DisplayName("★ 止めた発信も履歴に出て、理由が分かる")
    void includesBlockedCalls() throws Exception {
        var s = setup();

        var blocked = rows(fetch(s.managerToken(), "?kind=blocked"));

        assertThat(blocked).hasSize(1);
        assertThat(blocked.get(0).get("blocked_reason"))
            .as("「かけたが繋がらなかった」と「そもそもかけていない」は別物。"
                + "後者が見えないと、架電数が伸びない原因が分からない")
            .isEqualTo("do_not_call");
    }

    @Test
    @DisplayName("実際にかけた分だけに絞れる")
    void canFilterToDialedOnly() throws Exception {
        var s = setup();

        var dialed = rows(fetch(s.managerToken(), "?kind=dialed"));
        assertThat(dialed).hasSize(2);
        assertThat(dialed).allSatisfy(
            r -> assertThat(r.get("dial_state")).isNotEqualTo("blocked"));
    }

    @Test
    @DisplayName("★ 他テナントの通話は 1 件も見えない")
    void neverSeesOtherTenants() throws Exception {
        var s = setup();

        UUID other = createTenant("hist-other", "別会社");
        UUID otherUser = createUser(other, "x@example.com", "operator");
        UUID otherCustomer = createCustomer(other, "別会社の顧客");
        insertCall(other, otherCustomer, otherUser, "+81312349900", "completed", null);

        var page = fetch(s.managerToken(), "");

        assertThat(rows(page))
            .as("RLS が効いていないと、マネージャーには他社の通話まで見える")
            .hasSize(3);
        assertThat(page.get("total")).isEqualTo(3);
    }
}
