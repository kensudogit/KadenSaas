-- ============================================================================
-- テナントごとの電話設定。
--
-- ★ 発信者番号を画面から渡させない。渡させると、他人名義の番号を指定して
--   発信できてしまう。番号はテナントに紐づく設定として持ち、
--   発信時はここから引く。
--
-- ★ 認証情報（Auth Token / API Key Secret）をこの表に平文で置かない。
--   Twilio の Auth Token は「他人名義で電話をかけられる鍵」であり、
--   同時に Webhook の署名検証鍵でもある。漏れると発信と受信の両方が偽装できる。
--   Secrets Manager の参照キーだけを持ち、実体はアプリが起動時に解決する。
--
-- ★ サブアカウントを持てるようにしてある。テナントごとに Twilio の
--   サブアカウントを分けると、料金の按分と、1 テナントの不正利用を
--   そこだけで止めることができる。
-- ============================================================================

create table tenant_telephony (
  tenant_id        uuid primary key references tenants(id) on delete cascade,
  provider         text not null default 'twilio',
  -- 発信者番号（E.164）。購入済みまたは検証済みのものだけ
  caller_id        text not null,
  -- テナント専用のサブアカウント。無ければ親アカウントを使う
  subaccount_sid   text,
  -- ★ 実体ではなく参照。例: arn:aws:secretsmanager:...:secret/kaden/tenant/<id>
  credential_ref   text,
  -- 留守番電話の検出。DetectMessageEnd / Enable / なし
  machine_detection text not null default 'DetectMessageEnd',
  -- 通話を録音するか。テナントの契約と告知運用に依存する
  recording_enabled boolean not null default true,
  -- ★ 発信をテナント単位で止められるようにする。障害や苦情の対応で、
  --   全体を止めずに 1 社だけ止めたい場面が必ず来る
  dialing_enabled  boolean not null default true,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  constraint tenant_telephony_caller_id_shape check (caller_id ~ '^\+[1-9][0-9]{6,14}$'),
  constraint tenant_telephony_provider_valid check (provider in ('twilio'))
);

-- ★ 同じ発信者番号を 2 つのテナントで使わせない。
--   使えてしまうと、A 社の苦情で止めた番号から B 社が発信し続ける。
create unique index tenant_telephony_caller_id_uniq on tenant_telephony (caller_id);

grant select, insert, update on tenant_telephony to kaden_app;

-- ★ tenants と 1 対 1 だが RLS は掛けない（tenant_id が主キーで、
--   アプリは自テナントの id でしか引かない）。念のため参照系だけに絞る。
alter table tenant_telephony enable row level security;
alter table tenant_telephony force row level security;
create policy tenant_telephony_isolation on tenant_telephony
  using (tenant_id = app_current_tenant())
  with check (tenant_id = app_current_tenant());
