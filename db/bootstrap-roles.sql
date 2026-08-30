-- ============================================================================
-- マネージド Postgres 向けのロール設定（RDS / Aurora / Railway など）。
--
-- ★ マネージド DB が配る既定の接続ユーザーは superuser か所有者であることが多い。
--   そのまま DATABASE_URL に使うと、RLS が「書かれているのに 1 行も効かない」
--   状態になる。force row level security は superuser には効かないため。
--   アプリは正常に動いてしまうので、テストでも気付けない。
--
-- ★ 流し方:
--     psql "$ADMIN_DATABASE_URL" -f db/bootstrap-roles.sql --       -v app_password=xxxx -v migrator_password=yyyy
--
--   パスワードをこのファイルに書かない。履歴に残る。
-- ============================================================================

\set ON_ERROR_STOP on

-- ★ V6 のマイグレーションで作られている場合はパスワードだけ付ける
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'kaden_app') then
    create role kaden_app login;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'kaden_migrator') then
    create role kaden_migrator login bypassrls;
  end if;
end $$;

alter role kaden_app     password :'app_password';
alter role kaden_migrator password :'migrator_password';

-- ★ app 側に BYPASSRLS が付いていないことを確かめる。
--   付いた瞬間にテナント分離が消えるので、ここで落とす。
do $$
declare
  r record;
begin
  select rolsuper, rolbypassrls into r from pg_roles where rolname = 'kaden_app';
  if r.rolsuper or r.rolbypassrls then
    raise exception
      'kaden_app が superuser / BYPASSRLS です。この状態では RLS が効きません';
  end if;
end $$;

-- ★ データベース名はリテラルでしか書けないので、実行時に組み立てる
do $$
begin
  execute format('grant connect on database %I to kaden_app, kaden_migrator',
                 current_database());
end $$;

-- ★ PostgreSQL 15 以降、public スキーマの CREATE 権限が PUBLIC から外された。
--   データベース単位の grant だけでは足りない。これを忘れると Flyway が
--   「permission denied for schema public」で最初のテーブルすら作れず、
--   原因が権限の粒度だと気付くまで時間を溶かす。
grant usage, create on schema public to kaden_migrator;
grant usage on schema public to kaden_app;

\echo 'ロールを設定しました。DATABASE_URL を kaden_app、'
\echo 'DATABASE_MIGRATOR_URL を kaden_migrator に向けてください。'
