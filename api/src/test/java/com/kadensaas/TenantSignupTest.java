package com.kadensaas;

import com.kadensaas.service.TenantSignupService;
import com.kadensaas.service.TenantSignupService.SignupException;
import com.kadensaas.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * テナント登録。
 *
 * <p>★ ここは「まだテナントが決まっていない状態で、テナントを作る」という
 * 唯一の経路で、RLS の前提（app.tenant_id が設定されている）が成り立たない。
 * 実装は 2 つのトランザクションに分けて解決しているが、分け方を間違えると
 * 例外ではなく「登録できたのにログインできない」形で壊れる。
 *
 * <p>★ 失敗の理由が正しいことも確かめる。開発中、整合性違反を一律
 * 「識別子の重複」として返していたため、実際には not-null 違反なのに
 * 「その識別子は使われています」と嘘の理由が出て、原因の特定が遅れた。
 */
class TenantSignupTest extends AbstractIntegrationTest {

    @Autowired
    TenantSignupService signup;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("★ 登録するとテナントと管理者が両方できる")
    void createsTenantAndAdmin() {
        TenantContext.clear();   // 登録前は当然テナント未設定

        var r = signup.signup("あかね電気", "akane", "admin@akane.example",
            "Str0ng-Passw0rd!", "管理者");

        assertThat(r.tenantId()).isNotNull();
        assertThat(r.userId())
            .as("テナントだけできて利用者ができないと、誰もログインできない箱が残る")
            .isNotNull();

        Integer users = admin().queryForObject(
            "select count(*) from users where tenant_id = ?", Integer.class, r.tenantId());
        assertThat(users).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 登録直後のテナントの必須列がすべて埋まっている")
    void fillsRequiredColumns() {
        var r = signup.signup("あかね電気", "akane2", "admin@akane2.example",
            "Str0ng-Passw0rd!", "管理者");

        var t = admin().queryForMap("""
            select timezone, status, max_attempts_per_day, max_attempts_total,
                   to_char(calling_hours_start, 'HH24:MI') as starts,
                   to_char(calling_hours_end,   'HH24:MI') as ends,
                   calling_weekdays
              from tenants where id = ?
            """, r.tenantId());

        assertThat(t.get("timezone")).isEqualTo("Asia/Tokyo");
        assertThat(t.get("status")).isEqualTo("active");
        // ★ 壁時計の時刻が JVM のタイムゾーン分ずれていないこと。
        //   JPA 経由で書くと hibernate.jdbc.time_zone: UTC が time 型にも効き、
        //   JST の端末では 00:00-11:00 が入る。往復では辻褄が合うので
        //   アプリからは気付けず、SQL で読む診断画面だけがずれる
        assertThat(t.get("starts")).isEqualTo("09:00");
        assertThat(t.get("ends")).isEqualTo("20:00");
    }

    @Test
    @DisplayName("★ 登録したテナントとして、そのまま自分のデータが見える")
    void newTenantIsImmediatelyUsable() {
        var r = signup.signup("あかね電気", "akane3", "admin@akane3.example",
            "Str0ng-Passw0rd!", "管理者");

        // 登録処理が TenantContext を片付けていること（残すと次の利用者が
        // このテナントとして動く）
        assertThat(TenantContext.get())
            .as("スレッドはプールで使い回される。残すと次のリクエストに漏れる")
            .isNull();

        TenantContext.set(r.tenantId());
        Integer visible = admin().queryForObject(
            "select count(*) from tenants where id = ?", Integer.class, r.tenantId());
        assertThat(visible).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 同じ識別子は二度使えない")
    void rejectsDuplicateSlug() {
        signup.signup("あかね電気", "dup", "a@example.com", "Str0ng-Passw0rd!", "管理者");

        assertThatThrownBy(() ->
            signup.signup("べにや商会", "dup", "b@example.com", "Str0ng-Passw0rd!", "管理者"))
            .isInstanceOf(SignupException.class)
            .satisfies(e -> assertThat(((SignupException) e).code()).isEqualTo("slug_taken"));
    }

    @Test
    @DisplayName("★ 識別子の重複以外を slug_taken と報告しない（嘘の理由を出さない）")
    void doesNotMisreportOtherFailures() {
        assertThatThrownBy(() ->
            signup.signup("あかね電気", "ok-slug", "not-an-email",
                "Str0ng-Passw0rd!", "管理者"))
            .isInstanceOf(SignupException.class)
            .satisfies(e -> assertThat(((SignupException) e).code())
                .as("原因と違う理由を返すと、利用者も開発者も別の場所を疑う")
                .isEqualTo("invalid_email"));
    }

    @Test
    @DisplayName("弱いパスワードは登録できない")
    void rejectsWeakPassword() {
        assertThatThrownBy(() ->
            signup.signup("あかね電気", "weak", "a@example.com", "password", "管理者"))
            .isInstanceOf(SignupException.class)
            .satisfies(e -> assertThat(((SignupException) e).code()).isEqualTo("weak_password"));
    }

    @Test
    @DisplayName("★ 登録に失敗したテナントは残らない")
    void doesNotLeaveOrphanTenant() {
        int before = admin().queryForObject(
            "select count(*) from tenants", Integer.class);

        try {
            signup.signup("あかね電気", "orphan", "bad", "Str0ng-Passw0rd!", "管理者");
        } catch (SignupException expected) {
            // 想定どおり
        }

        int after = admin().queryForObject(
            "select count(*) from tenants", Integer.class);
        assertThat(after)
            .as("失敗したのに slug だけ占有されると、同じ識別子で作り直せない")
            .isEqualTo(before);
    }
}
