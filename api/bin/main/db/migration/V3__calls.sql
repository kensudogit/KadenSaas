-- ============================================================================
-- 通話。このスキーマの中心。
--
-- ★ 1 本の status 列に詰め込まない。性質の違う 3 つを別々に持つ。
--     dial_state   … 機械（Twilio）だけが書く技術的な状態
--     disposition  … 人／AI が書く業務結果
--     後処理       … 録音・文字起こし・AI 分析。それぞれのテーブルが持つ
--   1 列にすると「通話は終わったが文字起こしは処理中」が表現できず、
--   どちらかを上書きして壊れる。
--
-- ★ dial_state は単調前進しかしない。Twilio の webhook は順不同で届き、
--   再送もある。到着順に素直に代入すると completed のあとに ringing が来て
--   状態が巻き戻る。rank を生成列に持ち、UPDATE の where で弾く。
-- ============================================================================

-- ---------------------------------------------------------------- 結果コード

-- ★ マスタにする。KPI の SQL がリテラル文字列に依存すると、
--   コードを 1 つ足すたびに集計クエリを探して回ることになる。
--   分子・分母の定義をこの表の boolean 列に閉じ込めるのが要点。
create table disposition_codes (
  code            text primary key,
  label           text not null,
  -- 接続できたか。KPI の「接続率」の分子はここで決まる
  is_connected    boolean not null,
  -- 人と話せたか（留守電・受付止まりと区別する）
  is_conversation boolean not null,
  -- 成果とみなすか
  is_success      boolean not null default false,
  -- ★ これを選んだら二度とかけない。関門が参照する
  is_dnc          boolean not null default false,
  -- ★ 分母から外すか。無効な番号を分母に残すと、リストが汚いほど
  --   接続率が低く見える。外すなら無効番号率を併記する運用が要る
  excluded_from_denominator boolean not null default false,
  sort_order      int not null default 100
);

insert into disposition_codes
  (code, label, is_connected, is_conversation, is_success, is_dnc, excluded_from_denominator, sort_order)
values
  ('CONNECTED',      '担当者と会話',   true,  true,  false, false, false, 10),
  ('APPOINTMENT',    'アポイント獲得', true,  true,  true,  false, false, 20),
  ('CALLBACK',       '再架電希望',     true,  true,  false, false, false, 30),
  ('GATEKEEPER',     '受付で終了',     true,  true,  false, false, false, 40),
  ('NOT_INTERESTED', '断られた',       true,  true,  false, false, false, 50),
  ('DO_NOT_CALL',    '再勧誘拒否',     true,  true,  false, true,  false, 60),
  ('VOICEMAIL',      '留守番電話',     true,  false, false, false, false, 70),
  ('NO_ANSWER',      '応答なし',       false, false, false, false, false, 80),
  ('BUSY',           '話し中',         false, false, false, false, false, 90),
  ('INVALID_NUMBER', '無効な番号',     false, false, false, false, true,  100),
  ('FAILED',         '発信失敗',       false, false, false, false, true,  110),
  ('OTHER',          'その他',         true,  false, false, false, false, 120);

-- ---------------------------------------------------------------- 通話

-- ★ 単調前進のための順序。数値が大きいほど後の状態。
--   90 以上は終端で、そこからは動かない。
create or replace function call_dial_state_rank(state text) returns int
  language sql immutable as
  'select case $1
     when ''queued''    then 10
     when ''dialing''   then 20
     when ''ringing''   then 30
     when ''answered''  then 40
     when ''blocked''   then 90
     when ''completed'' then 90
     when ''busy''      then 90
     when ''no_answer'' then 90
     when ''failed''    then 90
     when ''canceled''  then 90
     else 0 end';

create table call_sessions (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenants(id) on delete cascade,
  campaign_id       uuid,
  call_target_id    uuid,
  customer_id       uuid not null,
  operator_id       uuid,
  -- ★ 通話の同一性はこれで担保する。Twilio の CallSid。
  --   自前の id で照合すると、webhook が先に届いたときに紐付かない
  provider          text not null default 'twilio',
  provider_call_sid text,
  direction         text not null default 'outbound',
  from_e164         text not null,
  to_e164           text not null,

  dial_state        text not null default 'queued',
  -- ★ 生成列。アプリが計算して入れると入れ忘れる
  dial_state_rank   int generated always as (call_dial_state_rank(dial_state)) stored,

  -- ★ 人／AI が書く。最新値のキャッシュで、正は call_dispositions の履歴
  disposition_code  text references disposition_codes(code),

  -- 関門が止めた理由。blocked のときだけ入る
  blocked_reason    text,

  started_at        timestamptz not null default now(),
  answered_at       timestamptz,
  ended_at          timestamptz,
  duration_seconds  int,

  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),

  constraint call_sessions_campaign_fk foreign key (tenant_id, campaign_id)
    references campaigns (tenant_id, id) on delete set null,
  constraint call_sessions_target_fk foreign key (tenant_id, call_target_id)
    references call_targets (tenant_id, id) on delete set null,
  constraint call_sessions_customer_fk foreign key (tenant_id, customer_id)
    references customers (tenant_id, id) on delete cascade,
  constraint call_sessions_operator_fk foreign key (tenant_id, operator_id)
    references users (tenant_id, id) on delete set null,
  constraint call_sessions_direction_valid check (direction in ('outbound', 'inbound')),
  constraint call_sessions_dial_state_valid check (call_dial_state_rank(dial_state) > 0),
  -- ★ blocked なら理由が要る。理由の無い blocked は後から説明できない
  constraint call_sessions_blocked_has_reason
    check (dial_state <> 'blocked' or blocked_reason is not null),
  constraint call_sessions_duration_nonneg
    check (duration_seconds is null or duration_seconds >= 0)
);

-- ★ 同じ CallSid が 2 行に増えるのを DB 側で止める。
--   webhook の取りこぼし対策で upsert する前提なので、これが無いと二重登録する
create unique index call_sessions_provider_sid_uniq
  on call_sessions (provider, provider_call_sid) where provider_call_sid is not null;

-- ★ 二重発信の防止。同じ相手に対して進行中の通話は 1 本だけ。
--   rank < 90 が「まだ終わっていない」。アプリ側の事前チェックは競合に弱いので
--   最後の砦を DB に置く
create unique index call_sessions_inflight_uniq
  on call_sessions (tenant_id, to_e164) where dial_state_rank < 90;

create index call_sessions_tenant_started_idx on call_sessions (tenant_id, started_at desc);
create index call_sessions_customer_idx on call_sessions (tenant_id, customer_id, started_at desc);
create index call_sessions_operator_idx on call_sessions (tenant_id, operator_id, started_at desc);
select app_enable_tenant_rls('call_sessions');
alter table call_sessions add constraint call_sessions_tenant_id_uniq unique (tenant_id, id);

-- ---------------------------------------------------------------- イベント

-- ★ transport 層の冪等。Twilio が同じ webhook を再送しても 1 回しか処理しない。
--   call_events と分けるのは、「受け取ったが業務的には捨てた」ものと
--   「そもそも届いていない」を区別するため。
--   tenant_id を持たない（署名検証の前段で、まだテナントが確定していない）。
create table webhook_events (
  id                uuid primary key default gen_random_uuid(),
  provider          text not null default 'twilio',
  -- Twilio の CallSid + イベント種別。プロバイダ側で一意になる組み合わせ
  provider_event_id text not null,
  received_at       timestamptz not null default now(),
  payload           jsonb not null
);

create unique index webhook_events_uniq on webhook_events (provider, provider_event_id);
create index webhook_events_received_idx on webhook_events (received_at);

-- ★ ドメインイベント。dial_state はこれの投影として決まる。
--   発信 API の同期レスポンスもここを通す（更新経路を 1 本にする）。
create table call_events (
  id               bigserial primary key,
  tenant_id        uuid not null references tenants(id) on delete cascade,
  call_session_id  uuid not null,
  -- api / status_callback / media_stream / operator / job
  source           text not null,
  dial_state       text,
  occurred_at      timestamptz not null default now(),
  -- ★ 単調前進で弾いた行も捨てずに残す。「なぜ反映されなかったか」を
  --   後から追えないと、順不同 webhook のデバッグができない
  applied          boolean not null default true,
  payload          jsonb,
  constraint call_events_session_fk foreign key (tenant_id, call_session_id)
    references call_sessions (tenant_id, id) on delete cascade,
  constraint call_events_source_valid
    check (source in ('api', 'status_callback', 'media_stream', 'operator', 'job'))
);

create index call_events_session_idx on call_events (tenant_id, call_session_id, occurred_at);
select app_enable_tenant_rls('call_events');

-- ---------------------------------------------------------------- 結果の履歴

-- ★ 訂正できるようにする。オペレーターは押し間違えるし、AI の判定も外れる。
--   上書きだけだと「誰がいつ何に変えたか」が消え、KPI の数字を説明できない。
create table call_dispositions (
  id               bigserial primary key,
  tenant_id        uuid not null references tenants(id) on delete cascade,
  call_session_id  uuid not null,
  code             text not null references disposition_codes(code),
  note             text,
  -- ★ 誰が入れたか。AI の下書きは recorded_by が null、source が ai
  source           text not null default 'operator',
  recorded_by      uuid,
  recorded_at      timestamptz not null default now(),
  constraint call_dispositions_session_fk foreign key (tenant_id, call_session_id)
    references call_sessions (tenant_id, id) on delete cascade,
  constraint call_dispositions_user_fk foreign key (tenant_id, recorded_by)
    references users (tenant_id, id) on delete set null,
  constraint call_dispositions_source_valid check (source in ('operator', 'ai', 'system')),
  -- ★ 人が入れたなら誰かが分からなければならない
  constraint call_dispositions_operator_has_user
    check (source <> 'operator' or recorded_by is not null)
);

create index call_dispositions_session_idx
  on call_dispositions (tenant_id, call_session_id, recorded_at desc);
select app_enable_tenant_rls('call_dispositions');

-- ---------------------------------------------------------------- 再架電

create table callbacks (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenants(id) on delete cascade,
  customer_id      uuid not null,
  call_session_id  uuid,
  -- ★ タイムゾーンを持つ型で保存する。timestamp（without time zone）にすると
  --   「14 時」がどこの 14 時か分からなくなり、架電時間帯の判定も狂う
  scheduled_at     timestamptz not null,
  reason           text,
  assigned_to      uuid,
  status           text not null default 'open',
  completed_at     timestamptz,
  created_at       timestamptz not null default now(),
  constraint callbacks_customer_fk foreign key (tenant_id, customer_id)
    references customers (tenant_id, id) on delete cascade,
  constraint callbacks_assignee_fk foreign key (tenant_id, assigned_to)
    references users (tenant_id, id) on delete set null,
  constraint callbacks_status_valid check (status in ('open', 'done', 'canceled'))
);

create index callbacks_due_idx on callbacks (tenant_id, scheduled_at) where status = 'open';
select app_enable_tenant_rls('callbacks');
