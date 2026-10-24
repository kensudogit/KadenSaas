-- ============================================================================
-- 着信オペレーター支援（コールセンター）。
--
-- ★ ここは問い合わせの受付・意図分類・ナレッジ出典・回答案だけを持つ。
--   call_sessions は作らない。DialingGate も通らない。
--   発信経路と混ぜると、画面の「回答案を作る」が誤って電話を鳴らす。
--
-- ★ ナレッジはテナントごとに持つ。他社の FAQ が見えると、
--   回答案に別テナントの案内が混ざる。
-- ============================================================================

create table knowledge_articles (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenants(id) on delete cascade,
  title       text not null,
  body        text not null,
  intent      text not null,
  created_at  timestamptz not null default now(),
  constraint knowledge_articles_intent_valid
    check (intent in ('billing', 'shipping', 'account', 'complaint', 'general'))
);

create unique index knowledge_articles_tenant_id_uniq
  on knowledge_articles (tenant_id, id);
select app_enable_tenant_rls('knowledge_articles');

create table knowledge_chunks (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenants(id) on delete cascade,
  article_id  uuid not null,
  content     text not null,
  intent      text not null,
  created_at  timestamptz not null default now(),
  constraint knowledge_chunks_article_fk
    foreign key (tenant_id, article_id)
    references knowledge_articles (tenant_id, id) on delete cascade
);

create unique index knowledge_chunks_tenant_id_uniq
  on knowledge_chunks (tenant_id, id);
create index knowledge_chunks_tenant_intent_idx
  on knowledge_chunks (tenant_id, intent);
select app_enable_tenant_rls('knowledge_chunks');

create table inbound_inquiries (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references tenants(id) on delete cascade,
  customer_id  uuid,
  operator_id  uuid,
  transcript   text not null,
  intent       text not null,
  reply        text not null,
  sources      jsonb not null default '[]',
  created_at   timestamptz not null default now(),
  constraint inbound_inquiries_customer_fk
    foreign key (tenant_id, customer_id)
    references customers (tenant_id, id) on delete set null,
  constraint inbound_inquiries_operator_fk
    foreign key (tenant_id, operator_id)
    references users (tenant_id, id) on delete set null
);

create unique index inbound_inquiries_tenant_id_uniq
  on inbound_inquiries (tenant_id, id);
create index inbound_inquiries_tenant_created_idx
  on inbound_inquiries (tenant_id, created_at desc);
select app_enable_tenant_rls('inbound_inquiries');

-- ★ V6 の default privileges が migrator 所有の新規表に効く想定だが、
--   明示しておかないと「表はあるのに 403/空」の切り分けが要る
grant select, insert, update, delete
  on knowledge_articles, knowledge_chunks, inbound_inquiries to kaden_app;
