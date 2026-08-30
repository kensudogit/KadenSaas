-- ============================================================================
-- ロールへの権限付与と KPI ビュー。
--
-- ★ ロールはここで「無ければ作る」。パスワードは付けない。
--   マネージド Postgres（RDS / Railway 等）では初期化スクリプトを差し込めず、
--   ロールが無いまま起動して RLS が素通りする事故が起きやすい。
--   スキーマと同じマイグレーションで作れば、その穴が塞がる。
--   パスワードの設定と接続文字列の切り替えは db/bootstrap-roles.sql で行う。
--
-- ★ KPI の定義はビューに閉じ込める。分母・分子を各サービスの SQL に
--   散らすと、Spring Boot 側と FastAPI 側で「接続率」が違う値になる。
--   ポリグロット構成では特に効く。
-- ============================================================================

-- ---------------------------------------------------------------- ロール

do $$
begin
  -- ★ アプリの接続ロール。RLS が「効く」側。
  --   BYPASSRLS を絶対に付けない。付けた時点でテナント分離が無くなる
  if not exists (select 1 from pg_roles where rolname = 'kaden_app') then
    create role kaden_app login;
  end if;

  -- ★ マイグレーションと集計ジョブ。RLS を迂回する。
  --   リクエスト処理からは使わない
  if not exists (select 1 from pg_roles where rolname = 'kaden_migrator') then
    create role kaden_migrator login bypassrls;
  end if;
end $$;

grant usage on schema public to kaden_app, kaden_migrator;

grant select, insert, update, delete on all tables in schema public to kaden_app;
grant usage, select on all sequences in schema public to kaden_app;
grant execute on all functions in schema public to kaden_app;

-- ★ これから作るテーブルにも自動で効かせる。付け忘れると
--   新しいテーブルだけ権限が無く、機能追加のたびに本番で気付くことになる
alter default privileges in schema public
  grant select, insert, update, delete on tables to kaden_app;
alter default privileges in schema public
  grant usage, select on sequences to kaden_app;

-- ★ 監査ログは追記のみ。消せる監査ログは監査にならない
revoke update, delete on audit_logs from kaden_app;
revoke update, delete on recording_access_logs from kaden_app;

-- ★ マスタはアプリから書き換えさせない
revoke insert, update, delete on disposition_codes from kaden_app;
revoke insert, update, delete on plans from kaden_app;

grant all on all tables in schema public to kaden_migrator;
grant all on all sequences in schema public to kaden_migrator;

-- ---------------------------------------------------------------- KPI

-- ★ 通話 1 本を KPI の観点で分類したビュー。ここが唯一の定義。
--   「接続率の分母に無効番号を含めるか」のような論争は、
--   この 1 ファイルを直せば全画面・全サービスに反映される形にしておく。
create view kpi_call_facts as
select
  cs.tenant_id,
  cs.id                          as call_session_id,
  cs.campaign_id,
  cs.operator_id,
  cs.customer_id,
  cs.started_at,
  (cs.started_at at time zone t.timezone)::date as local_date,
  extract(hour from cs.started_at at time zone t.timezone)::int as local_hour,
  cs.duration_seconds,
  cs.dial_state,
  cs.disposition_code,
  -- ★ 分母。ブロックした発信（そもそも鳴っていない）と、
  --   無効番号・システム起因の失敗は除く
  (cs.dial_state <> 'blocked'
     and coalesce(dc.excluded_from_denominator, false) = false) as counts_in_denominator,
  coalesce(dc.is_connected, false)     as is_connected,
  coalesce(dc.is_conversation, false)  as is_conversation,
  coalesce(dc.is_success, false)       as is_success,
  (cs.dial_state = 'blocked')          as was_blocked,
  cs.blocked_reason
from call_sessions cs
join tenants t on t.id = cs.tenant_id
left join disposition_codes dc on dc.code = cs.disposition_code;

-- ★ 日次サマリ。率は出さず、分子と分母を並べて返す。
--   率だけを返すと画面ごとに丸め方が変わり、また揉める。
--   「32.4%」ではなく「162 / 500」を渡し、表示側で組み立てさせる。
create view kpi_daily_summary as
select
  tenant_id,
  local_date,
  campaign_id,
  operator_id,
  count(*)                                          as attempts_total,
  count(*) filter (where counts_in_denominator)     as denominator,
  count(*) filter (where counts_in_denominator and is_connected)    as connected,
  count(*) filter (where counts_in_denominator and is_conversation) as conversations,
  count(*) filter (where counts_in_denominator and is_success)      as successes,
  count(*) filter (where was_blocked)               as blocked,
  coalesce(sum(duration_seconds) filter (where is_connected), 0) as talk_seconds,
  coalesce(
    avg(duration_seconds) filter (where is_connected and duration_seconds > 0), 0
  )::int as avg_talk_seconds
from kpi_call_facts
group by tenant_id, local_date, campaign_id, operator_id;

-- ★ 時間帯別。どの時間に鳴らすと繋がるかは、架電業務で最も効く分析。
create view kpi_hourly_connect as
select
  tenant_id,
  local_hour,
  count(*) filter (where counts_in_denominator)                  as denominator,
  count(*) filter (where counts_in_denominator and is_connected) as connected
from kpi_call_facts
group by tenant_id, local_hour;

grant select on kpi_call_facts, kpi_daily_summary, kpi_hourly_connect to kaden_app;

-- ★ ビューは定義者の権限で走るため、そのままだと RLS を素通りする。
--   security_invoker で「呼んだロールの権限」で評価させ、
--   ビュー越しに他テナントの数字が見える穴を塞ぐ。
alter view kpi_call_facts     set (security_invoker = true);
alter view kpi_daily_summary  set (security_invoker = true);
alter view kpi_hourly_connect set (security_invoker = true);
