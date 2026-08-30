-- ============================================================================
-- テナントと認証。すべての業務テーブルの土台。
--
-- ★ このプロジェクトのテナント分離は PostgreSQL の RLS で行う。
--   アプリ側の where 句に頼らない。where を 1 箇所書き忘れた瞬間に
--   他テナントのデータが漏れ、しかもテストが通ってしまうため。
--
-- ★ スキーマの所有者は Flyway（この api サービス）に一本化する。
--   voice サービス（FastAPI）は同じ DB を読み書きするがマイグレーションを
--   持たない。2 箇所から schema を変えると、片方だけ適用された状態が生まれる。
-- ============================================================================

-- ★ pgcrypto は使わない。gen_random_uuid() は PostgreSQL 13 以降
--   コアに入っているので拡張は不要。
--   create extension は superuser を要求するため、これを書くと
--   「マイグレーション用ロールに superuser が要る」という要件が生まれ、
--   その superuser は RLS を素通りするので、テナント分離の前提と衝突する。
--   拡張に依存しないほうが、マネージド Postgres でも素直に動く。

-- ---------------------------------------------------------------- RLS の基盤

-- ★ 接続ごとに SET LOCAL app.tenant_id で入れた値を読む。
--   missing_ok = true にしてあるのは、migrator が未設定で流すため。
--   未設定なら null を返し、ポリシーは 1 行も通さない（fail closed）。
create or replace function app_current_tenant() returns uuid
  language sql stable
  as $$ select nullif(current_setting('app.tenant_id', true), '')::uuid $$;

-- ★ テーブルを足すたびに 4 行書くのを忘れないための関数。
--   忘れると「ポリシーが無い＝全行見える」ではなく
--   「RLS 有効＋ポリシー無し＝1 行も見えない」になるので、
--   漏れるのではなく壊れる。壊れるほうが安全。
create or replace function app_enable_tenant_rls(target regclass) returns void
  language plpgsql as $$
begin
  execute format('alter table %s enable row level security', target);
  -- ★ force を付けないと、テーブル所有者（= migrator）には効かない
  execute format('alter table %s force row level security', target);
  execute format(
    'create policy tenant_isolation on %s using (tenant_id = app_current_tenant()) '
    'with check (tenant_id = app_current_tenant())', target);
end $$;

-- ---------------------------------------------------------------- tenants

create table tenants (
  id                    uuid primary key default gen_random_uuid(),
  name                  text not null,
  -- ★ ログインでどのテナントかを特定するための識別子（例: acme）。
  --   これが無いと「email から所属テナントを引く」ために RLS を迂回する
  --   経路が必要になり、その迂回が後で他の用途に流用されて穴になる。
  --   サブドメインや URL パスに現れる値なので、形を最初に固定しておく。
  slug                  text not null,
  -- 架電の関門で使う。テナントごとに違う（業種で許される時間帯が違う）
  timezone              text not null default 'Asia/Tokyo',
  calling_hours_start   time not null default '09:00',
  calling_hours_end     time not null default '20:00',
  calling_weekdays      int[] not null default '{1,2,3,4,5}',
  exclude_holidays      boolean not null default true,
  max_attempts_per_day  int not null default 3,
  max_attempts_total    int not null default 8,
  recording_retention_days int not null default 365,
  status                text not null default 'active',
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  constraint tenants_hours_ordered check (calling_hours_start < calling_hours_end),
  constraint tenants_status_valid check (status in ('active','suspended','closed'))
);

create unique index tenants_slug_uniq on tenants (lower(slug));
alter table tenants add constraint tenants_slug_shape
  check (slug ~ '^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$');

-- ★ tenants 自体には RLS を掛けない。掛けると自テナントの行すら
--   引けなくなる（tenant_id 列が無い）。代わりにアプリから直接は触らせず、
--   参照は id 指定に限る。

-- ---------------------------------------------------------------- users

create table users (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenants(id) on delete cascade,
  email          text not null,
  password_hash  text not null,
  display_name   text not null,
  -- operator: 架電する / manager: リストと結果を見る / admin: 設定と請求
  role           text not null default 'operator',
  status         text not null default 'active',
  last_seen_at   timestamptz,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint users_role_valid check (role in ('operator','manager','admin')),
  constraint users_status_valid check (status in ('active','disabled'))
);

-- ★ email はテナント内で一意。テナントをまたいだ一意にはしない
--   （別会社の担当者が同じアドレスを使うことは普通にある）
create unique index users_tenant_email_uniq on users (tenant_id, lower(email));
create index users_tenant_role_idx on users (tenant_id, role) where status = 'active';

select app_enable_tenant_rls('users');

-- ★ 複合外部キーで参照できるようにしておく。子テーブルが
--   (tenant_id, user_id) で参照すれば、別テナントの行を指す FK を
--   DB が拒否する。RLS と合わせて二重の防御になる
alter table users add constraint users_tenant_id_uniq unique (tenant_id, id);
