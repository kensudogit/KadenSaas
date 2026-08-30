package com.kadensaas.config;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * アプリの接続ロールで RLS が実際に効くことを起動時に確かめる。
 *
 * <p>★ なぜ必要か。{@code force row level security} はテーブル所有者には効くが、
 * <b>superuser と BYPASSRLS ロールには効かない</b>。
 * マネージド Postgres（Railway / RDS / Supabase など）が配る既定の接続ユーザーは
 * たいていそのどちらかで、{@code DATABASE_URL} をそのまま使うと
 * ポリシーは書かれているのに 1 行も効かない。
 *
 * <p>そして<b>アプリは完全に正常に動く</b>。画面も API も期待どおりに応答し、
 * テストも通る。違うのは「他テナントのデータも見えている」ことだけで、
 * それに気付く手がかりが無い。テナント分離だけが本番でだけ失われる。
 *
 * <p>だから起動を止める。動かないほうが、静かに漏れているよりましである。
 *
 * <p>★ voice サービス（{@code app/db/engine.py} の {@code assert_rls_enforced}）にも
 * 同じ検査がある。片方だけ守っても、もう片方から漏れる。
 *
 * <p>★ {@code @PostConstruct} でコンテキスト初期化中に実行する。
 * {@code ApplicationRunner} や {@code ApplicationReadyEvent} だと Tomcat が
 * 起動してリクエストを受け付けた後になり、止める前に応答してしまう。
 *
 * <p>★ Flyway の後に走らせる。マイグレーションは BYPASSRLS を持つ
 * {@code kaden_migrator} で流すのが正しく、そちらは検査の対象外。
 * ここで見るのはアプリが実際に使う接続。
 */
@Component
@DependsOn("flywayInitializer")
public class RlsEnforcementCheck {

    private static final Logger log = LoggerFactory.getLogger(RlsEnforcementCheck.class);

    /** 接続ロールが RLS を素通りする状態。production では起動を止める。 */
    public static class RlsNotEnforcedException extends IllegalStateException {
        public RlsNotEnforcedException(String message) {
            super(message);
        }
    }

    private final JdbcTemplate jdbc;
    private final String appEnv;

    public RlsEnforcementCheck(DataSource dataSource,
                               @Value("${kaden.app-env:${APP_ENV:development}}") String appEnv) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.appEnv = appEnv;
    }

    @PostConstruct
    public void verify() {
        Boolean isSuper;
        Boolean bypassRls;
        String role;

        try {
            var row = jdbc.queryForMap(
                "select current_user as role, rolsuper, rolbypassrls "
                + "from pg_roles where rolname = current_user");
            role = String.valueOf(row.get("role"));
            isSuper = (Boolean) row.get("rolsuper");
            bypassRls = (Boolean) row.get("rolbypassrls");
        } catch (Exception e) {
            // ★ 検査できないことを「問題なし」として扱わない。
            //   ただし pg_roles を読めないだけで起動を止めるのは過剰なので、
            //   警告に留める（権限を絞った構成では読めないことがある）
            log.warn("接続ロールの権限を確認できませんでした。"
                + "RLS が効いているかは未検証です: {}", e.getMessage());
            return;
        }

        if (!Boolean.TRUE.equals(isSuper) && !Boolean.TRUE.equals(bypassRls)) {
            log.info("RLS の検査に通りました（接続ロール: {}）", role);
            return;
        }

        String reason = Boolean.TRUE.equals(isSuper) ? "superuser" : "BYPASSRLS";
        String message = String.format(
            "アプリの接続ロール %s が %s のため、RLS が適用されません。"
            + "テナント分離が無効の状態です", role, reason);
        String hint =
            "db/bootstrap-roles.sql を流して kaden_app / kaden_migrator を作り、"
            + "DATABASE_URL を kaden_app に、DATABASE_MIGRATOR_URL を kaden_migrator に "
            + "向けてください";

        if ("production".equalsIgnoreCase(appEnv)) {
            log.error("{} — {}", message, hint);
            // ★ ここで止める。起動しないほうが、静かに漏れているよりよい
            throw new RlsNotEnforcedException(message + "。" + hint);
        }

        // 開発中は止めない。ローカルで migrator を使って動かすことがある
        log.warn("{} — {}（APP_ENV={} のため続行）", message, hint, appEnv);
    }
}
