# Railway で Flyway（V18）を直す

オートダイヤラーで「マイグレーション（Flyway）の適用を確認してください」と出るとき、
本番 DB に **V18（`auto_dialer_runs` 等）** が入っていません。

## 1. api の Deploy ログ

起動直後に次を確認:

- `Successfully applied ... V18__auto_dialer_ivr` → OK
- `must be owner of table` → [transfer-ownership.sql](./transfer-ownership.sql) を **Postgres の管理者接続**で 1 回実行してから再デプロイ
- `permission denied for schema public` → [bootstrap-roles.sql](./bootstrap-roles.sql) と migrator の CREATE 権限

## 2. api の環境変数

| 変数 | 内容 |
|------|------|
| `DATABASE_URL` | **kaden_app**（RLS 有効） |
| `DATABASE_MIGRATOR_URL` | **kaden_migrator**（BYPASSRLS・Flyway 専用） |
| `KADEN_VOICE_BASE_URL` | voice-web の公開 URL（発信時） |
| `JWT_SECRET` | voice と同一 |

`DATABASE_MIGRATOR_URL` が無いと、Flyway が app ロールで DDL し失敗することがあります。

## 3. Postgres で確認（Railway Query）

```sql
SELECT installed_rank, version, description, success
  FROM flyway_schema_history
 ORDER BY installed_rank DESC
 LIMIT 5;

SELECT to_regclass('public.auto_dialer_runs') AS auto_dialer_runs;
```

- `version = 18` かつ `auto_dialer_runs` が NULL でない → スキーマ OK（次は voice URL）
- 18 が無い → **最新 api を Redeploy**（Git に V18 入りコードがあること）

## 4. アプリから診断（manager / admin）

ログイン後:

```http
GET /api/v1/admin/schema/diagnose
Authorization: Bearer <token>
```

`readyForAutoDialer: true` なら DB は足りています。

## 5. ロール初回セットアップ

1. Railway Postgres の **管理者 URL** で `db/bootstrap-roles.sql`（パスワード変数付き）
2. 既存テーブルが postgres 所有なら `db/transfer-ownership.sql`
3. `DATABASE_URL` / `DATABASE_MIGRATOR_URL` を差し替え
4. api **Redeploy**
