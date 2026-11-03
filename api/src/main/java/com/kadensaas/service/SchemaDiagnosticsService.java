package com.kadensaas.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flyway / スキーマの状態を読み取り専用で確認する（本番トラブルシュート用）。
 */
@Service
public class SchemaDiagnosticsService {

    /** オートダイヤラーに必要な Flyway バージョン。 */
    public static final int REQUIRED_FOR_AUTO_DIALER = 20;

    public record Report(
        Integer flywayLatestVersion,
        String flywayLatestDescription,
        boolean flywayLatestSuccess,
        boolean autoDialerRunsTable,
        boolean campaignsAutoDialerColumn,
        boolean callSessionsConversationMode,
        boolean readyForAutoDialer,
        List<String> issues,
        List<String> actions) {
    }

    private final JdbcTemplate jdbc;

    public SchemaDiagnosticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Report diagnose() {
        var flyway = latestFlyway();
        boolean autoDialerRuns = tableExists("auto_dialer_runs");
        boolean campCol = columnExists("campaigns", "auto_dialer_enabled");
        boolean convCol = columnExists("call_sessions", "conversation_mode");

        int version = flyway.version() == null ? 0 : flyway.version();
        boolean flywayOk = flyway.success() && version >= REQUIRED_FOR_AUTO_DIALER;
        boolean ready = flywayOk && autoDialerRuns && campCol && convCol;

        List<String> issues = new ArrayList<>();
        List<String> actions = new ArrayList<>();

        if (!flyway.success()) {
            issues.add("Flyway の最新マイグレーションが success=false です");
            actions.add("api の Deploy ログで失敗した SQL を確認し、原因修正後に再デプロイ");
            actions.add("must be owner の場合は db/transfer-ownership.sql を superuser で 1 回実行");
        }
        if (version < REQUIRED_FOR_AUTO_DIALER) {
            issues.add("Flyway バージョンが " + version + " です（オートダイヤラーには "
                + REQUIRED_FOR_AUTO_DIALER + " 以上が必要）");
            actions.add("最新 api を再デプロイ（Flyway V17〜V19。V18 checksum mismatch は db/RAILWAY-FLYWAY.md）");
            actions.add("api に DATABASE_MIGRATOR_URL（kaden_migrator）を設定");
        }
        if (!autoDialerRuns) {
            issues.add("テーブル auto_dialer_runs がありません");
        }
        if (!campCol || !convCol) {
            issues.add("V18 の campaigns / call_sessions 列が不足しています");
        }
        if (actions.isEmpty() && !ready) {
            actions.add("api サービスを最新コミットで Redeploy");
        }
        if (ready) {
            actions.add("スキーマはオートダイヤラー対応済み。KADEN_VOICE_BASE_URL と JWT_SECRET を確認");
        }

        return new Report(
            flyway.version(),
            flyway.description(),
            flyway.success(),
            autoDialerRuns,
            campCol,
            convCol,
            ready,
            issues,
            actions.stream().distinct().toList());
    }

    private record FlywayRow(Integer version, String description, boolean success) {
    }

    private FlywayRow latestFlyway() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select version, description, success
              from flyway_schema_history
             order by installed_rank desc
             limit 1
            """);
        if (rows.isEmpty()) {
            return new FlywayRow(null, null, false);
        }
        Map<String, Object> r = rows.get(0);
        Integer ver = null;
        Object v = r.get("version");
        if (v instanceof Number n) {
            ver = n.intValue();
        } else if (v != null) {
            try {
                ver = Integer.parseInt(v.toString());
            } catch (NumberFormatException ignored) {
                ver = null;
            }
        }
        return new FlywayRow(
            ver,
            (String) r.get("description"),
            Boolean.TRUE.equals(r.get("success")));
    }

    private boolean tableExists(String name) {
        Boolean ok = jdbc.queryForObject(
            "select to_regclass(?) is not null", Boolean.class, "public." + name);
        return Boolean.TRUE.equals(ok);
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject("""
            select count(*) from information_schema.columns
             where table_schema = 'public' and table_name = ? and column_name = ?
            """, Integer.class, table, column);
        return n != null && n > 0;
    }
}
