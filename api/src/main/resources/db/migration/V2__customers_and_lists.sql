-- ============================================================================
-- 顧客・架電リスト・DNC。
--
-- ★ 電話番号は「表示用」と「E.164 正規化済み」を分けて持つ。
--   DNC 照合と重複検出は正規化側でしか成立しない。
--   03-1234-5678 と +81312345678 と 0312345678 は同じ相手だが、
--   文字列としては別物なので、表示用だけを持つと照合が素通りする。
-- ============================================================================

create table customers (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenants(id) on delete cascade,
  company_name  text,
  contact_name  text,
  email         text,
  -- 商談ステータス。架電の可否とは別物（可否は do_not_call_entries が持つ）
  status        text not null default 'new',
  owner_id      uuid,
  tags          text[] not null default '{}',
  note          text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  constraint customers_owner_fk foreign key (tenant_id, owner_id)
    references users (tenant_id, id) on delete set null
);

create index customers_tenant_status_idx on customers (tenant_id, status);
create index customers_tenant_owner_idx on customers (tenant_id, owner_id);
create index customers_tags_idx on customers using gin (tags);
select app_enable_tenant_rls('customers');
alter table customers add constraint customers_tenant_id_uniq unique (tenant_id, id);

-- ---------------------------------------------------------------- 電話番号

create table customer_phones (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenants(id) on delete cascade,
  customer_id    uuid not null,
  -- ★ 入力されたままの表示用。ハイフンや内線表記を保つ
  raw_number     text not null,
  -- ★ 照合・発信に使う唯一の値。アプリ側で phonenumbers により正規化する
  e164           text not null,
  -- main / mobile / work。督促では「勤務先へかけてよいか」が別管理になる
  kind           text not null default 'main',
  is_primary     boolean not null default false,
  created_at     timestamptz not null default now(),
  constraint customer_phones_customer_fk foreign key (tenant_id, customer_id)
    references customers (tenant_id, id) on delete cascade,
  constraint customer_phones_kind_valid check (kind in ('main','mobile','work','other')),
  constraint customer_phones_e164_shape check (e164 ~ '^\+[1-9][0-9]{6,14}$')
);

create unique index customer_phones_uniq on customer_phones (tenant_id, customer_id, e164);
create index customer_phones_e164_idx on customer_phones (tenant_id, e164);
-- ★ 主番号はひとつだけ。部分ユニークで DB 側から保証する
create unique index customer_phones_primary_uniq
  on customer_phones (tenant_id, customer_id) where is_primary;
select app_enable_tenant_rls('customer_phones');

-- ---------------------------------------------------------------- DNC

-- ★ 再勧誘禁止。架電の関門が必ず参照する。
--   customer 単位ではなく「番号」単位で持つ。顧客レコードを分割・統合しても
--   拒否の意思は番号に紐づいて残らなければならない。
create table do_not_call_entries (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references tenants(id) on delete cascade,
  e164         text not null,
  reason       text not null,
  -- 誰の申出か。監査で必ず聞かれる
  source       text not null default 'customer_request',
  note         text,
  created_by   uuid,
  created_at   timestamptz not null default now(),
  constraint dnc_source_valid check (source in ('customer_request','legal','internal','import')),
  constraint dnc_created_by_fk foreign key (tenant_id, created_by)
    references users (tenant_id, id) on delete set null
);

-- ★ 同じ番号を二重登録させない。照合は必ずここを引く
create unique index dnc_tenant_e164_uniq on do_not_call_entries (tenant_id, e164);
select app_enable_tenant_rls('do_not_call_entries');

-- ---------------------------------------------------------------- キャンペーン

create table campaigns (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references tenants(id) on delete cascade,
  name          text not null,
  script        text,
  status        text not null default 'draft',
  starts_at     timestamptz,
  ends_at       timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  constraint campaigns_status_valid check (status in ('draft','running','paused','done'))
);

create index campaigns_tenant_status_idx on campaigns (tenant_id, status);
select app_enable_tenant_rls('campaigns');
alter table campaigns add constraint campaigns_tenant_id_uniq unique (tenant_id, id);

-- ---------------------------------------------------------------- 架電対象

-- ★ call_targets は「かけるべき相手の 1 行」。call_sessions（1 回の通話）とは
--   1 対多。ここを 1 対 1 にすると再架電が表現できなくなる。
create table call_targets (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenants(id) on delete cascade,
  campaign_id    uuid not null,
  customer_id    uuid not null,
  phone_id       uuid not null,
  priority       int not null default 100,
  state          text not null default 'pending',
  assigned_to    uuid,
  -- ★ 予約（オペレーターが掴んでいる状態）の期限。
  --   ブラウザが落ちたときに解放されないと、リストが少しずつ枯れる
  reserved_until timestamptz,
  attempts       int not null default 0,
  attempts_today int not null default 0,
  last_attempt_at timestamptz,
  next_attempt_at timestamptz,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint call_targets_campaign_fk foreign key (tenant_id, campaign_id)
    references campaigns (tenant_id, id) on delete cascade,
  constraint call_targets_customer_fk foreign key (tenant_id, customer_id)
    references customers (tenant_id, id) on delete cascade,
  constraint call_targets_assignee_fk foreign key (tenant_id, assigned_to)
    references users (tenant_id, id) on delete set null,
  constraint call_targets_state_valid
    check (state in ('pending','reserved','calling','done','excluded'))
);

-- ★ 同じキャンペーンで同じ番号を二重に積まない
create unique index call_targets_uniq on call_targets (tenant_id, campaign_id, phone_id);
-- ★ 次にかける 1 件を引くための索引。pending だけを見る
create index call_targets_queue_idx
  on call_targets (tenant_id, campaign_id, priority, next_attempt_at)
  where state = 'pending';
create index call_targets_reserved_idx
  on call_targets (reserved_until) where state = 'reserved';
select app_enable_tenant_rls('call_targets');
alter table call_targets add constraint call_targets_tenant_id_uniq unique (tenant_id, id);
