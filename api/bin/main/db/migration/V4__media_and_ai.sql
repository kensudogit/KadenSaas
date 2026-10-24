-- ============================================================================
-- 録音・文字起こし・AI 分析。
--
-- ★ 音声の実体は DB に入れない。S3 に置き、ここにはメタデータだけを持つ。
--   録音は個人情報そのものなので、置き場所とアクセス経路を 1 箇所に絞り、
--   誰がいつ再生・ダウンロードしたかを必ず記録できる形にする。
--
-- ★ 後処理の進捗を call_sessions に持たせない。ここが独立したテーブルで
--   status を持つことで、「通話は終わったが文字起こしは処理中」が素直に表現できる。
--
-- ★ どの段階も再実行できるようにする。ASR も LLM も外部サービスなので、
--   落ちるのは異常ではなく通常運転。attempts と last_error を持たせ、
--   失敗した行を拾い直せるようにしておく。
-- ============================================================================

create table recordings (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenants(id) on delete cascade,
  call_session_id  uuid not null,
  -- Twilio 側の RecordingSid。再取得と重複防止に使う
  provider_recording_sid text,
  -- ★ 自前の保管先。Twilio 上の URL をそのまま使わない。
  --   プロバイダを変えた瞬間に過去の録音が全部引けなくなる
  storage_bucket   text,
  storage_key      text,
  content_type     text not null default 'audio/wav',
  duration_seconds int,
  size_bytes       bigint,
  status           text not null default 'pending',
  attempts         int not null default 0,
  last_error       text,
  -- ★ 保存期限。テナントの recording_retention_days から算出して入れる。
  --   「消す仕組み」を後から足すと、消し忘れた分が残り続ける
  retention_until  timestamptz,
  deleted_at       timestamptz,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  constraint recordings_session_fk foreign key (tenant_id, call_session_id)
    references call_sessions (tenant_id, id) on delete cascade,
  constraint recordings_status_valid
    check (status in ('pending', 'stored', 'failed', 'deleted')),
  -- ★ stored なら保管先が分からなければ意味がない
  constraint recordings_stored_has_location
    check (status <> 'stored' or (storage_bucket is not null and storage_key is not null))
);

create unique index recordings_provider_sid_uniq
  on recordings (provider_recording_sid) where provider_recording_sid is not null;
create index recordings_session_idx on recordings (tenant_id, call_session_id);
-- ★ 削除ジョブが引く索引。期限切れかつ未削除だけを見る
create index recordings_retention_idx
  on recordings (retention_until) where deleted_at is null and status = 'stored';
select app_enable_tenant_rls('recordings');
alter table recordings add constraint recordings_tenant_id_uniq unique (tenant_id, id);

-- ---------------------------------------------------------------- 文字起こし

create table transcripts (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenants(id) on delete cascade,
  call_session_id  uuid not null,
  recording_id     uuid,
  -- deepgram / google / azure / whisper。差し替えたときに
  -- 「どのエンジンで起こした文字か」が分からないと品質比較ができない
  engine           text not null,
  language         text not null default 'ja-JP',
  status           text not null default 'pending',
  -- 全文。検索は full_text に対して行う
  full_text        text,
  attempts         int not null default 0,
  last_error       text,
  started_at       timestamptz,
  completed_at     timestamptz,
  created_at       timestamptz not null default now(),
  constraint transcripts_session_fk foreign key (tenant_id, call_session_id)
    references call_sessions (tenant_id, id) on delete cascade,
  constraint transcripts_recording_fk foreign key (tenant_id, recording_id)
    references recordings (tenant_id, id) on delete set null,
  constraint transcripts_status_valid
    check (status in ('pending', 'running', 'done', 'failed'))
);

create unique index transcripts_session_uniq on transcripts (tenant_id, call_session_id);
create index transcripts_pending_idx on transcripts (status, created_at)
  where status in ('pending', 'failed');
select app_enable_tenant_rls('transcripts');
alter table transcripts add constraint transcripts_tenant_id_uniq unique (tenant_id, id);

-- ★ 発話単位。話者と時刻を持たせないと「どちらが何を言ったか」が
--   復元できず、会話の定量化（話した割合・沈黙・かぶり）ができない。
create table transcript_segments (
  id             bigserial primary key,
  tenant_id      uuid not null references tenants(id) on delete cascade,
  transcript_id  uuid not null,
  -- agent / customer。ステレオ録音のチャンネルか話者分離で決める
  speaker        text not null,
  start_ms       int not null,
  end_ms         int not null,
  text           text not null,
  confidence     real,
  constraint transcript_segments_transcript_fk foreign key (tenant_id, transcript_id)
    references transcripts (tenant_id, id) on delete cascade,
  constraint transcript_segments_speaker_valid
    check (speaker in ('agent', 'customer', 'unknown')),
  constraint transcript_segments_range_valid check (end_ms >= start_ms)
);

create index transcript_segments_idx on transcript_segments (tenant_id, transcript_id, start_ms);
select app_enable_tenant_rls('transcript_segments');

-- ---------------------------------------------------------------- AI 分析

-- ★ LLM の出力は構造化 JSON で受け、schema 検証を通してから保存する。
--   自由文で受けると、集計するたびにパースを書くことになる。
--
-- ★ そして「AI が出した値」と「人が確定した値」を必ず分ける。
--   AI の要約・スコアは下書きであって事実ではない。
--   reviewed_by が入って初めて確定として扱う。
create table ai_analyses (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenants(id) on delete cascade,
  call_session_id   uuid not null,
  transcript_id     uuid,
  model             text not null,
  prompt_version    text not null default 'v1',
  status            text not null default 'pending',

  summary           text,
  needs             text[] not null default '{}',
  objections        text[] not null default '{}',
  sentiment         text,
  -- 0-100。あくまで参考値。結果コードを上書きしない
  opportunity_score int,
  suggested_disposition text references disposition_codes(code),
  next_action       jsonb,
  recommended_talk  text,
  raw_response      jsonb,

  attempts          int not null default 0,
  last_error        text,

  -- ★ 人が見て承認・修正した記録。null のあいだは下書き扱い
  reviewed_by       uuid,
  reviewed_at       timestamptz,
  review_note       text,

  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),

  constraint ai_analyses_session_fk foreign key (tenant_id, call_session_id)
    references call_sessions (tenant_id, id) on delete cascade,
  constraint ai_analyses_transcript_fk foreign key (tenant_id, transcript_id)
    references transcripts (tenant_id, id) on delete set null,
  constraint ai_analyses_reviewer_fk foreign key (tenant_id, reviewed_by)
    references users (tenant_id, id) on delete set null,
  constraint ai_analyses_status_valid
    check (status in ('pending', 'running', 'done', 'failed')),
  constraint ai_analyses_sentiment_valid
    check (sentiment is null or sentiment in ('positive', 'neutral', 'negative')),
  constraint ai_analyses_score_range
    check (opportunity_score is null or opportunity_score between 0 and 100),
  constraint ai_analyses_review_pair
    check ((reviewed_by is null) = (reviewed_at is null))
);

create unique index ai_analyses_session_uniq on ai_analyses (tenant_id, call_session_id);
create index ai_analyses_pending_idx on ai_analyses (status, created_at)
  where status in ('pending', 'failed');
select app_enable_tenant_rls('ai_analyses');

-- ---------------------------------------------------------------- 会話の定量化

-- ★ 通話ごとの数値。KPI と別に持つのは、こちらが「会話の質」の指標で
--   集計の単位が違うため（KPI は日次・担当者別、こちらは通話単位）。
create table call_metrics (
  call_session_id     uuid primary key,
  tenant_id           uuid not null references tenants(id) on delete cascade,
  agent_talk_ms       int not null default 0,
  customer_talk_ms    int not null default 0,
  silence_ms          int not null default 0,
  overlap_ms          int not null default 0,
  -- 担当者が話した割合。高すぎる＝一方的に説明している合図
  agent_talk_ratio    real,
  longest_monologue_ms int,
  question_count      int,
  computed_at         timestamptz not null default now(),
  constraint call_metrics_session_fk foreign key (tenant_id, call_session_id)
    references call_sessions (tenant_id, id) on delete cascade
);

select app_enable_tenant_rls('call_metrics');

-- ---------------------------------------------------------------- 録音アクセス

-- ★ 録音を開いた記録。個人情報なので「見られる」ことより
--   「誰が見たか分かる」ことのほうが運用上効く。
create table recording_access_logs (
  id            bigserial primary key,
  tenant_id     uuid not null references tenants(id) on delete cascade,
  recording_id  uuid not null,
  user_id       uuid,
  action        text not null,
  ip            inet,
  accessed_at   timestamptz not null default now(),
  constraint recording_access_recording_fk foreign key (tenant_id, recording_id)
    references recordings (tenant_id, id) on delete cascade,
  constraint recording_access_user_fk foreign key (tenant_id, user_id)
    references users (tenant_id, id) on delete set null,
  constraint recording_access_action_valid check (action in ('play', 'download', 'delete'))
);

create index recording_access_idx on recording_access_logs (tenant_id, recording_id, accessed_at desc);
select app_enable_tenant_rls('recording_access_logs');
