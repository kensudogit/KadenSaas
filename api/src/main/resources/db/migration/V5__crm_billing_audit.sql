-- ============================================================================
-- CRM 連携・課金・監査ログ。SaaS 化に必要な部分。
--
-- ★ CRM の障害が架電を止めてはいけない。同期は必ず outbox 経由の非同期にし、
--   業務トランザクションの中で外部 API を呼ばない。Salesforce が 5 分落ちたら
--   その 5 分の架電が全部失敗する、という作りにしないこと。
--
-- ★ 外部 ID は専用の対応表に持つ。customers.salesforce_id のような列にすると、
--   Lead から Contact への変換や CRM の乗り換えが表現できなくなる。
-- ============================================================================

-- ---------------------------------------------------------------- CRM

create table crm_connections (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenants(id) on delete cascade,
  -- salesforce / hubspot / other
  provider       text not null,
  instance_url   text,
  -- ★ トークンはここに平文で置かない。KMS で暗号化した値か、
  --   Secrets Manager の参照キーだけを入れる
  credential_ref text,
  status         text not null default 'active',
  last_synced_at timestamptz,
  created_at     timestamptz not null default now(),
  constraint crm_connections_provider_valid check (provider in ('salesforce', 'hubspot', 'other')),
  constraint crm_connections_status_valid check (status in ('active', 'disabled', 'error'))
);

create unique index crm_connections_uniq on crm_connections (tenant_id, provider);
select app_enable_tenant_rls('crm_connections');

-- ★ 汎用の ID 対応表。どのローカル実体が CRM のどのレコードに対応するか。
create table crm_external_links (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenants(id) on delete cascade,
  provider       text not null,
  -- customer / call_session / callback
  entity_type    text not null,
  entity_id      uuid not null,
  -- 相手側のオブジェクト種別（Lead / Contact / Task ...）
  external_type  text not null,
  external_id    text not null,
  synced_at      timestamptz,
  created_at     timestamptz not null default now(),
  constraint crm_external_links_entity_valid
    check (entity_type in ('customer', 'call_session', 'callback'))
);

-- ★ ローカル 1 件が同じ provider の同じ種別に 2 つ紐づかないようにする
create unique index crm_external_links_local_uniq
  on crm_external_links (tenant_id, provider, entity_type, entity_id, external_type);
-- ★ 逆向きも一意。同じ外部レコードに 2 件がぶら下がると同期が往復する
create unique index crm_external_links_remote_uniq
  on crm_external_links (tenant_id, provider, external_type, external_id);
select app_enable_tenant_rls('crm_external_links');

-- ★ トランザクショナル outbox。業務トランザクションと同じコミットで
--   ここに 1 行入れ、送信は別プロセスが行う。これにより
--   「DB は更新されたが CRM に送られていない」も
--   「CRM に送ったが DB がロールバックした」も起きない。
create table crm_sync_outbox (
  id             bigserial primary key,
  tenant_id      uuid not null references tenants(id) on delete cascade,
  provider       text not null,
  entity_type    text not null,
  entity_id      uuid not null,
  operation      text not null,
  payload        jsonb not null,
  status         text not null default 'pending',
  attempts       int not null default 0,
  -- ★ 指数バックオフの次回実行時刻。すぐ再送し続けると
  --   相手の API 制限に当たって余計に復旧が遅れる
  next_attempt_at timestamptz not null default now(),
  last_error     text,
  -- ★ 同じ内容を 2 回送らないための鍵。相手側の upsert キーと対応させる
  dedup_key      text not null,
  created_at     timestamptz not null default now(),
  completed_at   timestamptz,
  constraint crm_sync_outbox_operation_valid check (operation in ('upsert', 'delete')),
  constraint crm_sync_outbox_status_valid
    check (status in ('pending', 'sending', 'done', 'failed', 'dead'))
);

create unique index crm_sync_outbox_dedup_uniq on crm_sync_outbox (tenant_id, provider, dedup_key);
create index crm_sync_outbox_due_idx on crm_sync_outbox (status, next_attempt_at)
  where status in ('pending', 'failed');
select app_enable_tenant_rls('crm_sync_outbox');

-- ---------------------------------------------------------------- 課金

create table plans (
  code            text primary key,
  name            text not null,
  -- ゼロ小数通貨。円は最小単位が 1 なので、100 倍しない
  monthly_price_jpy int not null,
  included_seats  int not null,
  included_minutes int not null,
  overage_per_minute_jpy int not null default 0,
  is_active       boolean not null default true
);

insert into plans (code, name, monthly_price_jpy, included_seats, included_minutes, overage_per_minute_jpy) values
  ('starter',  'Starter',   30000,   5,   3000, 12),
  ('business', 'Business', 120000,  25,  20000, 10),
  ('enterprise', 'Enterprise', 400000, 100, 80000, 8);

create table subscriptions (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references tenants(id) on delete cascade,
  plan_code          text not null references plans(code),
  -- Stripe の顧客・サブスクリプション ID
  provider           text not null default 'stripe',
  provider_customer_id     text,
  provider_subscription_id text,
  status             text not null default 'trialing',
  seats              int not null default 1,
  current_period_start timestamptz,
  current_period_end   timestamptz,
  canceled_at        timestamptz,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  constraint subscriptions_status_valid
    check (status in ('trialing', 'active', 'past_due', 'canceled'))
);

create unique index subscriptions_tenant_uniq on subscriptions (tenant_id);
create unique index subscriptions_provider_uniq
  on subscriptions (provider, provider_subscription_id)
  where provider_subscription_id is not null;
select app_enable_tenant_rls('subscriptions');

-- ★ 従量課金の元データ。通話時間を日次で締めて持つ。
--   call_sessions を毎回集計すると、過去分の訂正で請求額が変わってしまう。
create table usage_records (
  id             bigserial primary key,
  tenant_id      uuid not null references tenants(id) on delete cascade,
  usage_date     date not null,
  call_count     int not null default 0,
  billable_seconds bigint not null default 0,
  -- 締めたあとは変えない
  finalized_at   timestamptz,
  created_at     timestamptz not null default now()
);

create unique index usage_records_uniq on usage_records (tenant_id, usage_date);
select app_enable_tenant_rls('usage_records');

-- ★ Stripe の webhook も Twilio と同じ理由で冪等にする。
--   決済系は二重処理の実害が大きい。
create table billing_events (
  id                uuid primary key default gen_random_uuid(),
  provider          text not null default 'stripe',
  provider_event_id text not null,
  event_type        text not null,
  received_at       timestamptz not null default now(),
  processed_at      timestamptz,
  payload           jsonb not null
);

create unique index billing_events_uniq on billing_events (provider, provider_event_id);

-- ---------------------------------------------------------------- 監査ログ

-- ★ 「誰が・いつ・何に・何をしたか」。個人情報を扱う画面の操作と、
--   権限・設定の変更は必ずここに残す。
--   ★ append only。update / delete の権限をアプリロールに与えない
--     （V6 の grant で制御する）。
create table audit_logs (
  id           bigserial primary key,
  tenant_id    uuid not null references tenants(id) on delete cascade,
  user_id      uuid,
  action       text not null,
  entity_type  text,
  entity_id    uuid,
  -- 変更前後。個人情報そのものを入れない（キー名と差分の有無だけ）
  changes      jsonb,
  ip           inet,
  user_agent   text,
  occurred_at  timestamptz not null default now(),
  constraint audit_logs_user_fk foreign key (tenant_id, user_id)
    references users (tenant_id, id) on delete set null
);

create index audit_logs_tenant_time_idx on audit_logs (tenant_id, occurred_at desc);
create index audit_logs_entity_idx on audit_logs (tenant_id, entity_type, entity_id);
select app_enable_tenant_rls('audit_logs');
