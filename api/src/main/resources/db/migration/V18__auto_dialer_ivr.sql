-- オートダイヤラー / IVR 自動架電
-- ★ 適用済み環境では checksum を変えない（Flyway validate）。冪等化は V19。

alter table call_sessions
  add column if not exists conversation_mode text not null default 'operator';

alter table call_sessions
  drop constraint if exists call_sessions_conversation_mode_valid;

alter table call_sessions
  add constraint call_sessions_conversation_mode_valid
    check (conversation_mode in ('operator', 'ai_agent', 'ivr'));

update call_sessions
   set conversation_mode = 'ai_agent'
 where ai_agent_mode = true and conversation_mode = 'operator';

alter table campaigns
  add column if not exists auto_dialer_enabled boolean not null default false,
  add column if not exists auto_dialer_interval_seconds int not null default 45,
  add column if not exists ivr_enabled boolean not null default false,
  add column if not exists ivr_welcome_text text,
  add column if not exists ivr_menu_json jsonb;

comment on column campaigns.auto_dialer_enabled is
  'true ならオートダイヤラー run で順次発信できるキャンペーン';
comment on column campaigns.ivr_enabled is
  'true なら応答後に IVR（Gather）を流す';

create table if not exists auto_dialer_runs (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references tenants(id) on delete cascade,
  campaign_id         uuid not null,
  started_by          uuid,
  conversation_mode   text not null default 'operator',
  status              text not null default 'running',
  interval_seconds    int not null default 45,
  dials_attempted     int not null default 0,
  last_tick_at        timestamptz,
  last_call_session_id uuid,
  started_at          timestamptz not null default now(),
  stopped_at          timestamptz,
  constraint auto_dialer_runs_campaign_fk foreign key (tenant_id, campaign_id)
    references campaigns (tenant_id, id) on delete cascade,
  constraint auto_dialer_runs_status_valid
    check (status in ('running', 'paused', 'stopped')),
  constraint auto_dialer_runs_mode_valid
    check (conversation_mode in ('operator', 'ai_agent', 'ivr'))
);

create index if not exists auto_dialer_runs_campaign_idx
  on auto_dialer_runs (tenant_id, campaign_id, started_at desc);

select app_enable_tenant_rls('auto_dialer_runs');
alter table auto_dialer_runs add constraint auto_dialer_runs_tenant_id_uniq unique (tenant_id, id);
