package com.kadensaas;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 統合テストの土台。
 *
 * <p>★ 本物の PostgreSQL を使う。H2 では意味がない。このプロジェクトの
 * 最重要の性質（RLS によるテナント分離）は PostgreSQL の機能そのもので、
 * インメモリ DB で代用すると「テストは通るが本番で漏れる」を作るだけになる。
 *
 * <p>★ アプリの接続ロールを {@code kaden_app} にする。テストだけ superuser で
 * 動かすと RLS が素通りし、分離を検証しているつもりで何も見ていないことになる。
 *
 * <p>★ ロールはコンテナ起動直後に作る。V6 のマイグレーションも
 * {@code if not exists} で作るが、パスワードは付けない（本番では
 * {@code db/bootstrap-roles.sql} が付ける）。Spring が DataSource を
 * 作る前にパスワードが要るので、ここで先に済ませる。
 *
 * <p>★ コンテナはクラス間で使い回す。テストクラスごとに立て直すと
 * Flyway も毎回走り、実行時間が数分単位で伸びる。
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    protected static final String APP_PASSWORD = "test_app_pw";

    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("kaden")
            .withUsername("postgres")
            .withPassword("postgres");

    static {
        POSTGRES.start();
        bootstrapRoles();
    }

    /** 本番の db/bootstrap-roles.sql と同じことをする。 */
    private static void bootstrapRoles() {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement()) {

            st.execute("create role kaden_app login password '" + APP_PASSWORD + "'");
            st.execute("create role kaden_migrator login bypassrls password 'test_mig_pw'");
            // ★ PostgreSQL 15 以降、public スキーマの CREATE は PUBLIC から外れている
            st.execute("grant usage, create on schema public to kaden_migrator");
            st.execute("grant usage on schema public to kaden_app");
        } catch (Exception e) {
            throw new IllegalStateException("テスト用ロールを作成できませんでした", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Flyway は superuser で流す（grant と revoke を含むため）
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // ★ アプリは kaden_app。ここを postgres にすると RLS が効かず、
        //   分離のテストが常に通ってしまう
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "kaden_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);

        registry.add("kaden.jwt.secret", () -> "test-secret-0123456789abcdef0123456789");
        registry.add("kaden.signup.token", () -> "test-signup-token");
    }

    /** RLS を迂回してデータを作る前準備用。 */
    protected JdbcTemplate admin() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(ds);
    }

    @BeforeEach
    void cleanBusinessData() {
        // ★ テスト間でデータを持ち越さない。superuser で消すので RLS を跨げる
        JdbcTemplate jdbc = admin();
        for (String table : new String[] {
            "call_dispositions", "call_events", "call_sessions", "call_targets",
            "callbacks", "do_not_call_entries", "customer_phones", "customers",
            "campaigns", "audit_logs", "tenant_telephony", "users", "tenants"
        }) {
            jdbc.update("delete from " + table);
        }
    }

    // ------------------------------------------------------------ 前準備

    protected java.util.UUID createTenant(String slug, String name) {
        return admin().queryForObject("""
            insert into tenants (name, slug, timezone, calling_hours_start,
                                 calling_hours_end, calling_weekdays)
            values (?, ?, 'Asia/Tokyo', '00:00', '23:59', '{1,2,3,4,5,6,7}')
            returning id
            """, java.util.UUID.class, name, slug);
    }

    protected java.util.UUID createUser(java.util.UUID tenantId, String email, String role) {
        return admin().queryForObject("""
            insert into users (tenant_id, email, password_hash, display_name, role)
            values (?, ?, 'x', ?, ?)
            returning id
            """, java.util.UUID.class, tenantId, email, email, role);
    }

    protected java.util.UUID createCustomer(java.util.UUID tenantId, String company) {
        return admin().queryForObject("""
            insert into customers (tenant_id, company_name, status)
            values (?, ?, 'new')
            returning id
            """, java.util.UUID.class, tenantId, company);
    }

    protected java.util.UUID createPhone(java.util.UUID tenantId,
                                         java.util.UUID customerId, String e164) {
        return admin().queryForObject("""
            insert into customer_phones (tenant_id, customer_id, raw_number, e164, kind, is_primary)
            values (?, ?, ?, ?, 'main', true)
            returning id
            """, java.util.UUID.class, tenantId, customerId, e164, e164);
    }

    protected void configureTelephony(java.util.UUID tenantId, String callerId,
                                      boolean dialingEnabled) {
        admin().update("""
            insert into tenant_telephony (tenant_id, caller_id, dialing_enabled)
            values (?, ?, ?)
            on conflict (tenant_id) do update set
              caller_id = excluded.caller_id,
              dialing_enabled = excluded.dialing_enabled
            """, tenantId, callerId, dialingEnabled);
    }
}
