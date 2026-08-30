package com.kadensaas;

import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.security.JwtService;
import com.kadensaas.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 認証されていない（401）と、権限が足りない（403）を分ける。
 *
 * <p>★ 既定では両方 403 になる。すると画面側は「トークンが切れた」のか
 * 「権限が無い」のかを区別できない。{@code web/lib/api.ts} は 401 のときだけ
 * トークンを捨ててログイン画面へ戻すので、期限切れが 403 で返ると
 * 利用者は「エラー (403)」の画面から抜け出せなくなる。
 * ログアウトを押すか localStorage を消すまで詰む。実際に本番がその状態だった。
 *
 * <p>★ 401 は「ログインし直せば解決する」、403 は「別の人に頼め」。
 * 利用者が取るべき行動が違うので、必ず分ける。
 */
@AutoConfigureMockMvc
class AuthResponseCodeTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("★ トークンが無ければ 401（403 ではない）")
    void missingTokenIsUnauthenticated() throws Exception {
        int status = mvc.perform(get("/api/v1/customers"))
            .andReturn().getResponse().getStatus();

        assertThat(status)
            .as("403 を返すと、画面がログインへ戻せず利用者が詰む")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("★ 壊れた／期限切れのトークンも 401")
    void invalidTokenIsUnauthenticated() throws Exception {
        int status = mvc.perform(get("/api/v1/customers")
                .header("Authorization", "Bearer not.a.valid.token"))
            .andReturn().getResponse().getStatus();

        assertThat(status)
            .as("期限切れはログインし直せば直る。401 でなければ画面がそう案内できない")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("★ 認証済みで権限が足りない場合は 403")
    void authenticatedButForbiddenIs403() throws Exception {
        UUID tenantId = createTenant("authcode", "権限コード社");
        UUID userId = createUser(tenantId, "op@example.com", "operator");
        String token = jwt.issue(
            new AuthUser(userId, tenantId, "op@example.com", AuthUser.Role.OPERATOR));

        int status = mvc.perform(get("/api/v1/analytics/hourly")
                .header("Authorization", "Bearer " + token))
            .andReturn().getResponse().getStatus();

        assertThat(status)
            .as("ログインし直しても解決しない。401 を返すと無限にログインさせることになる")
            .isEqualTo(403);
    }

    @Test
    @DisplayName("★ 401 の本文が画面の読み方とそろっている")
    void unauthenticatedBodyMatchesTheAgreedShape() throws Exception {
        String body = mvc.perform(get("/api/v1/customers"))
            .andReturn().getResponse().getContentAsString();

        // ★ ApiExceptionHandler と同じ形。形が 2 通りあると、
        //   画面が「エラーの読み方」を 2 つ持つことになる
        assertThat(body).contains("\"error\"").contains("\"message\"");
    }

    @Test
    @DisplayName("ルートは認証なしで開ける（URL を間違えた人に分かるように）")
    void rootIsReachableWithoutToken() throws Exception {
        var response = mvc.perform(get("/")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("kaden-api");
    }

    @Test
    @DisplayName("★ ルートに内部情報を出さない")
    void rootLeaksNothing() throws Exception {
        String body = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();

        // ★ 認証なしで見られる場所に、攻撃者が使える情報を置かない
        assertThat(body)
            .doesNotContain("version")
            .doesNotContain("0.1.0")
            .doesNotContain("postgres")
            .doesNotContain("jdbc");
    }
}
