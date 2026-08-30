-- ============================================================================
-- ローカル（docker-compose）の初回起動時だけ走る。
--
-- ★ マネージド Postgres には初期化スクリプトを差し込めないので、
--   同じロール作成を V6 のマイグレーションにも入れてある。
--   こちらはパスワードを付ける点だけが違う。
-- ============================================================================

create role kaden_app login password 'kaden_app_dev';
create role kaden_migrator login bypassrls password 'kaden_migrator_dev';

grant connect on database kaden to kaden_app, kaden_migrator;

-- ★ PostgreSQL 15 以降、public スキーマの CREATE 権限が PUBLIC から
--   外された。データベース単位の grant だけでは足りず、
--   スキーマに対して明示的に CREATE を渡さないと Flyway が
--   「permission denied for schema public」で 1 つ目のテーブルすら作れない。
--   14 以前しか触ったことがないと必ず踏む。
grant usage, create on schema public to kaden_migrator;
grant usage on schema public to kaden_app;
