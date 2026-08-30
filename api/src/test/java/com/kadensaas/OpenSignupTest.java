package com.kadensaas;

import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.service.CallService;
import com.kadensaas.service.SignupRateLimiter;
import com.kadensaas.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 公開サインアップ。
 *
 * <p><b>★ このテストの主目的は「誰でも登録できる」ことの確認ではなく、
 * 「登録しただけでは 1 本も発信できない」ことの固定である。</b>
 *
 * <p>誰でも登録できるということは、誰でも架電の基盤を持てるということでもある。
 * それを成立させているのは、登録処理が {@code tenant_telephony}（発信者番号）を
 * 作らないこと。作らないので関門が {@code telephony_not_configured} で止める。
 * ここが崩れると、この製品は「誰でも迷惑電話をかけられる基盤」になる。
 * 謝って済む種類の不具合ではないので、実際に発信を試して確かめる。
 *
 * <p>★ 回数制限も確かめる。無いと 1 分間に数千のテナントを作られる。
 */
@AutoConfigureMockMvc
class OpenSignupTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CallService callService;

    @Autowired
    SignupRateLimiter rateLimiter;

    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        // ★ 回数制限は DB に貯まる。テスト間で持ち越すと後続が 429 になる
        admin().update("delete from signup_attempts");
    }

    private String body(String slug, String email) {
        return """
            {"tenantName":"テスト商事","slug":"%s","email":"%s",
             "password":"Str0ng-Passw0rd!","displayName":"管理者"}
            """.formatted(slug, email);
    }

    private org.springframework.test.web.servlet.ResultActions register(
            String slug, String email, String ip) throws Exception {
        return mvc.perform(post("/api/v1/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", ip)
            .content(body(slug, email)));
    }

    @Test
    @DisplayName("合言葉なしで登録できる（公開サインアップ）")
    void anyoneCanRegister() throws Exception {
        var response = register("openco", "admin@openco.example", "203.0.113.10")
            .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).contains("openco");
    }

    @Test
    @DisplayName("登録できることを画面が問い合わせられる")
    void availabilityIsAdvertised() throws Exception {
        String body = mvc.perform(get("/api/v1/signup/available"))
            .andReturn().getResponse().getContentAsString();

        var node = json.readTree(body);
        assertThat(node.get("enabled").asBoolean()).isTrue();
        assertThat(node.get("requiresToken").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★ 登録した直後のテナントは 1 本も発信できない")
    void freshTenantCannotDial() throws Exception {
        var response = register("dialco", "admin@dialco.example", "203.0.113.11")
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);

        var created = json.readTree(response.getContentAsString());
        UUID tenantId = UUID.fromString(created.get("tenantId").asText());
        UUID userId = UUID.fromString(created.get("userId").asText());

        // 登録した本人として、架電先を用意して発信を試みる
        UUID customerId = createCustomer(tenantId, "かけたい相手");
        UUID phoneId = createPhone(tenantId, customerId, "+81312349500");

        TenantContext.set(tenantId);
        var user = new AuthUser(userId, tenantId, "admin@dialco.example",
            AuthUser.Role.ADMIN);

        var result = callService.requestDial(user, phoneId, null, null);

        assertThat(result.accepted())
            .as("誰でも登録できる以上、ここが通ると誰でも迷惑電話をかけられる。"
                + "登録と発信可能性は必ず分けておく")
            .isFalse();
        assertThat(result.blockedReason()).isEqualTo("telephony_not_configured");
    }

    @Test
    @DisplayName("★ 登録処理は発信者番号を作らない")
    void signupCreatesNoTelephonyRow() throws Exception {
        var response = register("noteleco", "admin@noteleco.example", "203.0.113.12")
            .andReturn().getResponse();
        var created = json.readTree(response.getContentAsString());
        UUID tenantId = UUID.fromString(created.get("tenantId").asText());

        Integer rows = admin().queryForObject(
            "select count(*) from tenant_telephony where tenant_id = ?",
            Integer.class, tenantId);

        assertThat(rows)
            .as("発信者番号は購入・検証が必要で、自己申告で登録させてよいものではない")
            .isZero();
    }

    @Test
    @DisplayName("★ 同じ IP から作れる数に上限がある")
    void limitsRegistrationsPerIp() throws Exception {
        String ip = "203.0.113.20";
        int accepted = 0;
        int limited = 0;

        // 上限（既定 5）を超えるまで叩く
        for (int i = 0; i < 8; i++) {
            int status = register("burst" + i, "admin" + i + "@burst.example", ip)
                .andReturn().getResponse().getStatus();
            if (status == 201) {
                accepted++;
            } else if (status == 429) {
                limited++;
            }
        }

        assertThat(limited)
            .as("上限が無いと、1 分間に数千のテナントを作られる")
            .isGreaterThan(0);
        assertThat(accepted)
            .as("正当な登録まで止めては困る")
            .isGreaterThan(0);
    }

    @Test
    @DisplayName("★ 失敗した試行も回数に数える")
    void countsFailedAttemptsToo() throws Exception {
        String ip = "203.0.113.21";

        // ★ 常に失敗する登録（識別子の形式違反）を繰り返す。
        //   成功だけ数えていると、この総当たりが素通りする
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/api/v1/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", ip)
                .content(body("A", "admin@bad.example")));
        }

        int status = register("legit", "admin@legit.example", ip)
            .andReturn().getResponse().getStatus();

        assertThat(status)
            .as("失敗を数えないと、どの識別子が空いているかを無制限に調べられる")
            .isEqualTo(429);
    }

    @Test
    @DisplayName("別の IP は互いの上限に影響しない")
    void limitIsPerIp() throws Exception {
        for (int i = 0; i < 6; i++) {
            register("noisy" + i, "a" + i + "@noisy.example", "203.0.113.30");
        }

        int status = register("quiet", "admin@quiet.example", "203.0.113.31")
            .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(201);
    }

    @Test
    @DisplayName("古い試行の記録は消せる（IP を残し続けない）")
    void oldAttemptsCanBePurged() {
        rateLimiter.record("203.0.113.40", "old", false, "test");
        admin().update(
            "update signup_attempts set created_at = now() - interval '40 days'");

        int deleted = rateLimiter.purgeOlderThanDays(30);

        assertThat(deleted).isGreaterThan(0);
    }
}
