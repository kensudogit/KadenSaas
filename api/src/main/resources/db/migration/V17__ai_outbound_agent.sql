-- AI が発信・会話するモード（人のオペレーターに代わる音声エージェント）

alter table campaigns
  add column if not exists ai_agent_enabled boolean not null default false;

comment on column campaigns.ai_agent_enabled is
  'true のキャンペーンは AI 自動架電 UI の対象。スクリプト（script）を会話の指示に使う。';

alter table call_sessions
  add column if not exists ai_agent_mode boolean not null default false;

comment on column call_sessions.ai_agent_mode is
  'true の通話は voice 側で相手発話→LLM→TTS のループを回す。operator_id は起票者の監査用に残してよい。';
