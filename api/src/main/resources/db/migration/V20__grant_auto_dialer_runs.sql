-- kaden_app が auto_dialer_runs を触れるようにする。
-- ★ V18 を superuser / 別ロールで適用した Railway では default privileges が効かず
--   permission denied になる（実測）。

grant select, insert, update, delete on table public.auto_dialer_runs to kaden_app;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'kaden_migrator') then
    execute $ddl$
      alter default privileges for role kaden_migrator in schema public
        grant select, insert, update, delete on tables to kaden_app
    $ddl$;
    execute $ddl$
      alter default privileges for role kaden_migrator in schema public
        grant usage, select on sequences to kaden_app
    $ddl$;
  end if;
end $$;
