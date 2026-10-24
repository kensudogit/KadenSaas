package com.kadensaas;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kadensaas.security.AuthUser;
import com.kadensaas.security.JwtService;
import com.kadensaas.service.callcenter.CallCenterAssistService;
import com.kadensaas.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 着信オペレーター支援。
 *
 * <p>★ 発信しないこと、出典の無い回答を捏造しないこと、
 * 他テナントのナレッジが見えないことを固定する。
 */
@AutoConfigureMockMvc
class CallCenterAssistTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    @Autowired
    CallCenterAssistService assist;

    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("日本語の請求問い合わせは出典付きで billing になる")
    void japaneseBillingHitsFaq() throws Exception {
        String token = readyTenant("cc-a", "着信A社");

        var response = mvc.perform(post("/api/v1/call-center/assist")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"請求書が届きません。再発行できますか。\"}"))
            .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("intent").asText()).isEqualTo("billing");
        assertThat(body.get("hits").isEmpty()).isFalse();
        assertThat(body.get("hits").get(0).get("intent").asText()).isEqualTo("billing");
        assertThat(body.get("reply").asText()).contains("出典");
        assertThat(body.get("trainingUse").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("該当が無いとき FAQ を捏造しない")
    void emptyDoesNotInvent() throws Exception {
        String token = readyTenant("cc-b", "着信B社");

        var response = mvc.perform(post("/api/v1/call-center/assist")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"spaceship widget xyzzy\"}"))
            .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("hits").isEmpty()).isTrue();
        assertThat(body.get("reply").asText()).contains("該当");
    }

    @Test
    @DisplayName("★ 回答案を作っても発信行は増えない")
    void assistDoesNotDial() throws Exception {
        String token = readyTenant("cc-c", "着信C社");
        long before = countSessions();

        mvc.perform(post("/api/v1/call-center/assist")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"請求書が届きません。再発行できますか。\"}"))
            .andReturn();

        assertThat(countSessions())
            .as("コールセンター画面から call_sessions が増えてはいけない")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("語彙が重ならなくても請求意図なら billing ナレッジを参照できる")
    void intentAlignedWhenLexicalMisses() {
        String token = readyTenant("cc-align", "着信整合社");

        var response = mvc.perform(post("/api/v1/call-center/assist")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"支払いの明細が欲しいです。\"}"))
            .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("intent").asText()).isEqualTo("billing");
        assertThat(body.get("hits").isEmpty()).isFalse();
        assertThat(body.get("hits").get(0).get("intent").asText()).isEqualTo("billing");
        assertThat(body.get("note").asText()).contains("intent_aligned");
    }

    @Test
    @DisplayName("FAQ が無いテナントでも回答案を作るとデモ FAQ が入り RAG する")
    void assistAutoSeedsKnowledge() {
        UUID tenantId = createTenant("cc-auto", "自動FAQ社");
        UUID userId = createUser(tenantId, "auto@example.com", "operator");
        TenantContext.set(tenantId);
        var user = new AuthUser(userId, tenantId, "auto@example.com", AuthUser.Role.OPERATOR);
        var result = assist.assist(user, "請求書が届きません。再発行できますか。", null);
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.intent()).isEqualTo("billing");
        assertThat(assist.listKnowledge()).hasSize(5);
    }

    @Test
    @DisplayName("FAQ シードはテナント単位で空のときだけ入る")
    void seedKnowledgeIsPerTenant() {
        UUID a = createTenant("cc-seed-a", "FAQ A");
        UUID b = createTenant("cc-seed-b", "FAQ B");
        TenantContext.set(a);
        assertThat(assist.seedKnowledge(a)).isEqualTo(5);
        assertThat(assist.seedKnowledge(a)).isZero();

        TenantContext.set(b);
        assertThat(assist.seedKnowledge(b)).isEqualTo(5);
    }

    @Test
    @DisplayName("デモ用 FAQ と受付履歴をまとめて入れられる")
    void seedDemoIfEmpty() {
        UUID tenantId = createTenant("cc-demo-seed", "着信デモ");
        UUID userId = createUser(tenantId, "cc-demo@example.com", "admin");
        TenantContext.set(tenantId);
        var user = new AuthUser(userId, tenantId, "cc-demo@example.com", AuthUser.Role.ADMIN);
        var seed = assist.seedDemoIfEmpty(user);
        assertThat(seed.knowledgeArticlesAdded()).isEqualTo(5);
        assertThat(seed.demoInquiriesAdded()).isEqualTo(3);
        assertThat(assist.listKnowledge()).hasSize(5);
        assertThat(assist.listInquiries(10)).hasSize(3);
    }

    @Test
    @DisplayName("他テナントのナレッジは見えない")
    void knowledgeIsTenantScoped() {
        UUID a = createTenant("cc-own", "自社");
        UUID b = createTenant("cc-other", "他社");
        TenantContext.set(a);
        assist.seedKnowledge(a);

        TenantContext.set(b);
        assertThat(assist.listKnowledge())
            .as("他テナントの FAQ が読めると回答案に混ざる")
            .isEmpty();
    }

    private String readyTenant(String slug, String name) {
        UUID tenantId = createTenant(slug, name);
        UUID userId = createUser(tenantId, slug + "@example.com", "operator");
        TenantContext.set(tenantId);
        assist.seedKnowledge(tenantId);
        TenantContext.clear();
        return jwt.issue(new AuthUser(userId, tenantId, slug + "@example.com", AuthUser.Role.OPERATOR));
    }

    private long countSessions() {
        Long n = admin().queryForObject("select count(*) from call_sessions", Long.class);
        return n == null ? 0 : n;
    }
}
