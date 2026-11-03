# Railway 本番トラブル（Flyway / CORS）

## CORS で「Failed to fetch」

ブラウザの Console に

`No 'Access-Control-Allow-Origin' header`

と出るとき、**api が落ちている**ことが多いです（CORS 設定以前に Tomcat が起動していない）。
Deploy ログで `Application run failed` / Flyway を先に確認してください。

api が起動している場合は **api** の Variables:

```env
CORS_ORIGIN=https://web-production-7e530.up.railway.app
```

（web の公開 URL を **https 付き・末尾スラッシュなし**で。複数ならカンマ区切り）

---

## Flyway V18 checksum mismatch

ログ例:

```text
Migration checksum mismatch for migration version 18
-> Applied to database : -62650964
-> Resolved locally    : -1663396168
```

**原因:** すでに Railway に適用された V18 SQL をリポジトリで書き換えた。
**対応:** リポジトリは V18 を元に戻し、追加変更は **V19** に置く（本リポジトリの現行構成）。
そのうえで **api を Redeploy** する。

まだ validate で落ちる場合のみ、Postgres で checksum を合わせる:

```sql
UPDATE flyway_schema_history
   SET checksum = -62650964
 WHERE version = '18';
```

（値は Deploy ログの `Applied to database` の行を使う）

---

## オートダイヤラーの DB エラー

本番 DB に **V18（`auto_dialer_runs` 等）** が入っていないとき、
「マイグレーション（Flyway）の適用を確認してください」と出ます。

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
