package com.kadensaas;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * マイグレーションが本番と同じロールで走っていることを固定する。
 *
 * <p>★ これは「テストの前提」を検査するテストである。ここが崩れると、
 * 他のテストが全部通っていても、マイグレーションが本番で通る保証は無くなる。
 *
 * <p>★ きっかけになった失敗。テストでは Flyway を superuser（postgres）で
 * 流していた。superuser は所有権の検査を素通りするので、
 * {@code alter table} を含む V10 はローカルでは通り、本番では
 * <pre>ERROR: must be owner of table call_sessions</pre>
 * で落ちた。本番の V1〜V9 は superuser で適用済みでテーブルが postgres 所有、
 * その後 Flyway を kaden_migrator に切り替えていたため、
 * 既存テーブルを変更する最初のマイグレーションで初めて表面化した。
 *
 * <p>★ PostgreSQL では権限（grant）と所有権（owner）が別物で、
 * {@code all privileges} を持っていても所有者でなければ
 * {@code alter} も {@code drop} もできない。テストが superuser で走る限り、
 * この違いは本番でしか分からない。
 */
class SchemaOwnershipTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("★ マイグレーションは kaden_migrator が流している（superuser ではない）")
    void migrationsRunAsMigrator() {
        List<String> owners = admin().queryForList("""
            select distinct tableowner from pg_tables where schemaname = 'public'
            """, String.class);

        assertThat(owners)
            .as("テーブルの所有者が kaden_migrator でないなら、Flyway が別のロールで"
                + "走っている。superuser で走らせると所有権の検査を素通りするので、"
                + "alter table を含むマイグレーションが本番で初めて落ちる")
            .containsExactly("kaden_migrator");
    }

    @Test
    @DisplayName("★ kaden_migrator は既存テーブルを変更できる")
    void migratorCanAlterExistingTables() {
        // ★ V10 が本番で落ちたのと同じ操作。所有権が無ければここで
        //   must be owner of table になる
        admin().execute("""
            do $$
            begin
              execute 'set local role kaden_migrator';
              execute 'alter table call_sessions add column if not exists
                         __ownership_probe text';
              execute 'alter table call_sessions drop column if exists
                         __ownership_probe';
            end $$
            """);
    }

    @Test
    @DisplayName("★ アプリのロールは RLS を素通りしない")
    void appRoleDoesNotBypassRls() {
        var row = admin().queryForMap("""
            select rolsuper, rolbypassrls from pg_roles where rolname = 'kaden_app'
            """);

        assertThat(row.get("rolsuper"))
            .as("superuser には force row level security が効かない。"
                + "この状態ではテナント分離のテストが全部無意味になる")
            .isEqualTo(false);
        assertThat(row.get("rolbypassrls")).isEqualTo(false);
    }
}
