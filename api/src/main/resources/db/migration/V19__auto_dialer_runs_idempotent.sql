-- V18 適用後の手動修復・再実行に耐える（本番は V18 成功後に 1 回だけ走る）

do $$
begin
  if not exists (
    select 1 from pg_policies
     where schemaname = 'public'
       and tablename = 'auto_dialer_runs'
       and policyname = 'tenant_isolation'
  ) then
    perform app_enable_tenant_rls('auto_dialer_runs');
  end if;
end $$;

do $$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'auto_dialer_runs_tenant_id_uniq'
  ) then
    alter table auto_dialer_runs
      add constraint auto_dialer_runs_tenant_id_uniq unique (tenant_id, id);
  end if;
end $$;
