-- public スキーマのオブジェクトの所有者を kaden_migrator にそろえる。
--
-- ★ 一度だけ、superuser で流す。Flyway のマイグレーションには入れられない。
--   所有権の変更には所有者か superuser の権限が要り、
--   kaden_migrator 自身には実行できないため。
--
-- ★ なぜ必要か。
--   PostgreSQL では ALTER TABLE / ALTER VIEW / DROP に「所有権」が要る。
--   grant で与えられるのは select/insert/update/delete などの権限だけで、
--   all privileges を持っていても所有者でなければ ALTER はできない。
--
--   このプロジェクトでは、最初のデプロイで Flyway が postgres として走り、
--   V1〜V9 のテーブルはすべて postgres 所有になった。その後 Flyway の
--   接続を kaden_migrator に切り替えたが、所有権は postgres のまま残った。
--   新規作成（create table）は通るので誰も気付かず、既存のテーブルを
--   変更する最初のマイグレーション（V10）で初めて
--     ERROR: must be owner of table call_sessions
--   として表面化した。
--
--   これを直さないと、今後 ALTER や DROP を含むマイグレーションは
--   1 つも適用できない。
--
-- ★ reassign owned by postgres to kaden_migrator は使わない。
--   public スキーマの外にある postgres 所有のオブジェクトまで巻き込む。
--   マネージドな PostgreSQL では、管理用のオブジェクトが何を含むか
--   こちらからは分からない。対象を public スキーマに限定する。

-- ★ スキーマ自体の所有権も移す。V6 の grant usage on schema public は
--   スキーマの所有権を要求するため、これが無いと将来の
--   スキーマ単位の grant を含むマイグレーションが通らない。
alter schema public owner to kaden_migrator;

do $$
declare
  r record;
  moved int := 0;
begin
  if not exists (select 1 from pg_roles where rolname = 'kaden_migrator') then
    raise exception 'kaden_migrator が存在しません。先に db/bootstrap-roles.sql を流してください';
  end if;

  -- テーブル・ビュー・シーケンス
  --
  -- ★ テーブルに紐づくシーケンス（bigserial や identity が作るもの）は
  --   除く。単独では所有者を変えられず、
  --     cannot change owner of sequence ... is linked to table ...
  --   になる。テーブルの所有者を変えれば自動的に付いてくる。
  --
  -- ★ 並び順に意味がある。relkind で 'S' を後ろにして、
  --   テーブルを先に処理する
  for r in
    select c.relname, c.relkind
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public'
       and c.relkind in ('r', 'p', 'v', 'm', 'S')
       and c.relowner <> 'kaden_migrator'::regrole
       and not (
         c.relkind = 'S'
         and exists (
           select 1 from pg_depend d
            where d.classid = 'pg_class'::regclass
              and d.objid = c.oid
              and d.deptype in ('a', 'i')))
     order by case c.relkind when 'S' then 2 else 1 end, c.relname
  loop
    execute format(
      'alter %s public.%I owner to kaden_migrator',
      case r.relkind
        when 'v' then 'view'
        when 'm' then 'materialized view'
        when 'S' then 'sequence'
        else 'table'
      end,
      r.relname);
    moved := moved + 1;
  end loop;

  -- 関数（V1 の app_enable_tenant_rls、V3 の call_dial_state_rank、
  -- V9 の call_sessions_lookup など）
  for r in
    select p.oid::regprocedure as sig
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and p.proowner <> 'kaden_migrator'::regrole
  loop
    execute format('alter function %s owner to kaden_migrator', r.sig);
    moved := moved + 1;
  end loop;

  raise notice '所有者を kaden_migrator に変更したオブジェクト: % 件', moved;
end $$;

-- ★ 確認。0 件でなければ、まだ他の所有者のオブジェクトが残っている
select count(*) as "kaden_migrator 以外が所有するオブジェクト（期待 0）"
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'public'
   and c.relkind in ('r', 'p', 'v', 'm')
   and c.relowner <> 'kaden_migrator'::regrole;

-- ★ アプリ用ロールの権限は所有権の移動では失われないが、念のため確かめる。
--   ここが 0 だと、画面は 200 を返しながら 1 行も出ない状態になる。
select count(*) as "kaden_app が select できるテーブル数（期待 20 以上）"
  from information_schema.table_privileges
 where grantee = 'kaden_app' and privilege_type = 'SELECT';
