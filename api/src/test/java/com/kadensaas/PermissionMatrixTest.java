package com.kadensaas;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.security.JwtService;
import com.kadensaas.security.PermissionCatalog;
import com.kadensaas.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 権限一覧が実装とずれていないことを確かめる。
 *
 * <p>★ 管理画面の「権限」は {@link PermissionCatalog} を表示しているだけで、
 * 実際に権限を決めているのは SecurityConfig と各 {@code @PreAuthorize}。
 * 放っておけば必ずずれる。そして、ずれた権限表は
 * 「画面には admin only と書いてあるのに実は operator でも通る」という
 * いちばん質の悪い嘘になる。誰も嘘だと気付かないまま運用される。
 *
 * <p>★ そこで、一覧の各項目が持つ実際の入口を 3 つの役割すべてで叩き、
 * 宣言と実測が一致するかを見る。403 が返るかどうかだけを見る。
 * 認可の検査であって、機能の検査ではないため、400 や 404 は問題にしない。
 *
 * <p>★ ただし全役割が 400 になる項目は「検査できていない」ものとして落とす。
 * Spring MVC は引数の解決を先に行い、{@code @PreAuthorize} はその後に走るので、
 * 必須パラメータや本文が足りないと認可に到達する前に 400 で返る。
 * それを「通った」と数えると、権限を検査したつもりで何も見ていないことになる。
 * 実際、最初は dnc.remove がこの状態だった。
 */
@AutoConfigureMockMvc
class PermissionMatrixTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private record Actor(AuthUser.Role role, String token) {
    }

    private List<Actor> actors() {
        UUID tenantId = createTenant("perm-test", "権限テスト社");
        List<Actor> list = new ArrayList<>();
        for (AuthUser.Role role : AuthUser.Role.values()) {
            String email = role.name().toLowerCase() + "@example.com";
            UUID userId = createUser(tenantId, email, role.name().toLowerCase());
            var principal = new AuthUser(userId, tenantId, email, role);
            list.add(new Actor(role, jwt.issue(principal)));
        }
        return list;
    }

    @Test
    @DisplayName("★ 権限一覧の全項目が、実際の挙動と一致する")
    void catalogMatchesEnforcement() throws Exception {
        var actors = actors();
        List<String> mismatches = new ArrayList<>();
        List<String> untested = new ArrayList<>();

        for (var capability : PermissionCatalog.ALL) {
            boolean reachedHandler = false;

            for (var actor : actors) {

                var request = MockMvcRequestBuilders
                    .request(HttpMethod.valueOf(capability.method()), capability.probeUrl())
                    .header("Authorization", "Bearer " + actor.token());
                if (!capability.probeBody().isEmpty()) {
                    request = request
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(capability.probeBody());
                }

                int status = mvc.perform(request).andReturn().getResponse().getStatus();
                if (status != 400) {
                    reachedHandler = true;
                }

                // ★ 403 だけが「拒否された」。400 や 404 や 500 は認可を通過している
                boolean permitted = status != 403;
                boolean declared = capability.allows(actor.role());

                if (permitted != declared) {
                    mismatches.add(String.format(
                        "%s（%s）: %s は画面上「%s」だが、実際は %s（HTTP %d）",
                        capability.key(), capability.label(),
                        actor.role().name().toLowerCase(),
                        declared ? "許可" : "不可",
                        permitted ? "通った" : "403 で拒否された",
                        status));
                }

                // ★ 401 が出たらトークンの作り方が壊れている。
                //   全項目が 401 だと「全部拒否＝宣言どおり」に見えてしまい、
                //   この試験そのものが何も検査しなくなる
                assertThat(status)
                    .as("%s に %s のトークンで 401。試験の前提（トークン発行）が壊れている",
                        capability.path(), actor.role())
                    .isNotEqualTo(401);
            }

            if (!reachedHandler) {
                untested.add(String.format(
                    "%s（%s %s）: 全役割で 400。引数が足りず認可に到達していない",
                    capability.key(), capability.method(), capability.probeUrl()));
            }
        }

        assertThat(untested)
            .as("探針が認可まで届いていない項目がある。probeQuery / probeBody を"
                + "足して、引数解決を通るようにすること:" + System.lineSeparator()
                + String.join(System.lineSeparator(), untested))
            .isEmpty();

        assertThat(mismatches)
            .as("権限一覧と実装がずれている。画面の表が嘘になっているので、"
                + "PermissionCatalog か SecurityConfig のどちらかを直すこと:\n%s",
                String.join("\n", mismatches))
            .isEmpty();
    }

    @Test
    @DisplayName("★ 認証なしでは通らない")
    void rejectsAnonymous() throws Exception {
        for (var capability : PermissionCatalog.ALL) {
            int status = mvc.perform(MockMvcRequestBuilders
                    .request(HttpMethod.valueOf(capability.method()), capability.probeUrl()))
                .andReturn().getResponse().getStatus();

            assertThat(status)
                .as("%s がトークン無しで通った", capability.path())
                .isIn(401, 403);
        }
    }

    @Test
    @DisplayName("権限一覧の項目に重複や記載漏れがない")
    void catalogIsWellFormed() {
        var keys = PermissionCatalog.ALL.stream().map(c -> c.key()).toList();
        assertThat(keys).doesNotHaveDuplicates();

        for (var c : PermissionCatalog.ALL) {
            assertThat(c.roles()).as("%s に許可役割が無い", c.key()).isNotEmpty();
            assertThat(c.detail())
                .as("%s に理由が書かれていない。表だけ見て納得できる必要がある", c.key())
                .isNotBlank();
        }
    }
}
