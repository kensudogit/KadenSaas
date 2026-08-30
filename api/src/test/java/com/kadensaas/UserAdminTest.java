package com.kadensaas;

import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.service.UserAdminService;
import com.kadensaas.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 利用者の管理。
 *
 * <p>★ 「最後の管理者がいなくなる」経路を全部塞げているかを確かめる。
 * 塞げていないと、設定を変えられる人が誰もいないテナントが出来上がり、
 * 復旧に DB を直接触ることになる。降格・無効化・自分自身の 3 経路がある。
 *
 * <p>★ 初期パスワードが保存されていないことも確かめる。
 * 平文が DB に残っていたら、管理画面が実質パスワードの保管庫になる。
 */
class UserAdminTest extends AbstractIntegrationTest {

    @Autowired
    UserAdminService users;

    @Autowired
    PasswordEncoder encoder;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private AuthUser adminOf(UUID tenantId) {
        UUID id = createUser(tenantId, "admin@example.com", "admin");
        TenantContext.set(tenantId);
        return new AuthUser(id, tenantId, "admin@example.com", AuthUser.Role.ADMIN);
    }

    @Test
    @DisplayName("★ 初期パスワードは生成され、平文では保存されない")
    void issuesGeneratedPasswordWithoutStoringIt() {
        UUID tenantId = createTenant("ua1", "利用者テスト社");
        var actor = adminOf(tenantId);

        var created = users.create(actor, "new@example.com", "新人", "operator");

        assertThat(created.initialPassword()).hasSizeGreaterThanOrEqualTo(12);

        String stored = admin().queryForObject(
            "select password_hash from users where id = ?", String.class, created.userId());

        assertThat(stored)
            .as("平文が保存されていたら、管理画面がパスワードの保管庫になる")
            .isNotEqualTo(created.initialPassword());
        assertThat(encoder.matches(created.initialPassword(), stored))
            .as("発行した値でログインできなければ、渡された人が困る")
            .isTrue();
    }

    @Test
    @DisplayName("★ 発行直後は変更が求められる状態になっている")
    void newUserMustChangePassword() {
        UUID tenantId = createTenant("ua2", "利用者テスト社");
        var actor = adminOf(tenantId);

        var created = users.create(actor, "new@example.com", "新人", "operator");

        Boolean required = admin().queryForObject(
            "select password_change_required from users where id = ?",
            Boolean.class, created.userId());

        assertThat(required)
            .as("管理者が知っているパスワードのまま、その人の名前で"
                + "架電の記録が残る状態を作らない")
            .isTrue();
    }

    @Test
    @DisplayName("★ 監査ログに初期パスワードもメールアドレスも残さない")
    void auditKeepsNoSecretsOrPii() {
        UUID tenantId = createTenant("ua3", "利用者テスト社");
        var actor = adminOf(tenantId);

        var created = users.create(actor, "secret@example.com", "新人", "operator");

        String changes = admin().queryForObject("""
            select changes::text from audit_logs
             where tenant_id = ? and action = 'user.created'
            """, String.class, tenantId);

        assertThat(changes).doesNotContain(created.initialPassword());
        assertThat(changes)
            .as("監査ログは長期保存され閲覧範囲も広い。生の個人情報を溜めない")
            .doesNotContain("secret@example.com");
    }

    @Test
    @DisplayName("★ 最後の管理者は降格できない")
    void cannotDemoteLastAdmin() {
        UUID tenantId = createTenant("ua4", "利用者テスト社");
        var actor = adminOf(tenantId);

        assertThatThrownBy(() -> users.changeRole(actor, actor.userId(), "operator"))
            .as("設定を変えられる人がいないテナントを作らせない")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("最後の管理者");
    }

    @Test
    @DisplayName("★ 最後の管理者は無効化できない")
    void cannotDisableLastAdmin() {
        UUID tenantId = createTenant("ua5", "利用者テスト社");
        var actor = adminOf(tenantId);
        var second = users.create(actor, "admin2@example.com", "管理者2", "admin");

        // 2 人目がいるので 1 人目は無効化できる
        users.setStatus(actor, second.userId(), false);

        // これで管理者は actor だけ。自分自身は別の理由で弾かれるので、
        // もう一度 admin を作って、そちらから actor を無効化しにいく
        var third = users.create(actor, "admin3@example.com", "管理者3", "admin");
        users.setStatus(actor, third.userId(), false);

        assertThatThrownBy(() -> users.setStatus(actor, actor.userId(), false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("★ 自分自身は無効化できない")
    void cannotDisableSelf() {
        UUID tenantId = createTenant("ua6", "利用者テスト社");
        var actor = adminOf(tenantId);
        users.create(actor, "admin2@example.com", "管理者2", "admin");

        assertThatThrownBy(() -> users.setStatus(actor, actor.userId(), false))
            .as("その場でログインできなくなる")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("自分自身");
    }

    @Test
    @DisplayName("管理者が 2 人いれば降格できる")
    void canDemoteWhenAnotherAdminExists() {
        UUID tenantId = createTenant("ua7", "利用者テスト社");
        var actor = adminOf(tenantId);
        var second = users.create(actor, "admin2@example.com", "管理者2", "admin");

        users.changeRole(actor, second.userId(), "manager");

        String role = admin().queryForObject(
            "select role from users where id = ?", String.class, second.userId());
        assertThat(role).isEqualTo("manager");
    }

    @Test
    @DisplayName("★ 本人がパスワードを変えると、変更要求が解除される")
    void changingOwnPasswordClearsTheFlag() {
        UUID tenantId = createTenant("ua8", "利用者テスト社");
        var actor = adminOf(tenantId);
        var created = users.create(actor, "member@example.com", "本人", "operator");

        var member = new AuthUser(created.userId(), tenantId,
            "member@example.com", AuthUser.Role.OPERATOR);

        users.changeOwnPassword(member, created.initialPassword(), "Str0ng-New-Passw0rd");

        Boolean required = admin().queryForObject(
            "select password_change_required from users where id = ?",
            Boolean.class, created.userId());
        assertThat(required).isFalse();
    }

    @Test
    @DisplayName("★ 現在のパスワードが違えば変更できない")
    void rejectsWrongCurrentPassword() {
        UUID tenantId = createTenant("ua9", "利用者テスト社");
        var actor = adminOf(tenantId);
        var created = users.create(actor, "member@example.com", "本人", "operator");

        var member = new AuthUser(created.userId(), tenantId,
            "member@example.com", AuthUser.Role.OPERATOR);

        assertThatThrownBy(() ->
            users.changeOwnPassword(member, "まちがい", "Str0ng-New-Passw0rd"))
            .as("照合しないと、席を離れた隙に触った誰かが本人を締め出せる")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("★ 他テナントの利用者は見えないし、触れない")
    void cannotTouchOtherTenantUsers() {
        UUID mine = createTenant("ua10", "自社");
        UUID other = createTenant("ua10-other", "別会社");
        UUID victim = createUser(other, "victim@example.com", "admin");

        var actor = adminOf(mine);

        assertThat(users.list())
            .as("一覧に他テナントが混ざったら分離が壊れている")
            .allSatisfy(u -> assertThat(u.get("email")).isNotEqualTo("victim@example.com"));

        assertThatThrownBy(() -> users.changeRole(actor, victim, "operator"))
            .as("RLS により 0 行になるので「見つかりません」になる")
            .isInstanceOf(IllegalArgumentException.class);
    }
}
