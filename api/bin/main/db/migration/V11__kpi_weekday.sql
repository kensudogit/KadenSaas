-- 分析（曜日別）のために kpi_call_facts に曜日を足す。
--
-- ★ 曜日はテナントのタイムゾーンでの曜日でなければならない。
--   サーバーの日付で数えると、深夜帯の通話が前日・翌日に寄る。
--   local_date が既にテナントのタイムゾーンで出しているので、
--   そこから導く（now() を再度使わない）。
--
-- ★ 集計の定義はこのビューが唯一の出所、という約束は変えない。
--   分析画面は counts_in_denominator / is_connected などの
--   このビューの列をそのまま使い、独自に「接続とは何か」を定義しない。
--
-- ★ create or replace view は末尾への列追加のみ許される。
--   途中に入れると依存ビュー（kpi_daily_summary など）ごと作り直しになる。

create or replace view kpi_call_facts as
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
  (cs.dial_state <> 'blocked'
     and coalesce(dc.excluded_from_denominator, false) = false) as counts_in_denominator,
  coalesce(dc.is_connected, false)     as is_connected,
  coalesce(dc.is_conversation, false)  as is_conversation,
  coalesce(dc.is_success, false)       as is_success,
  (cs.dial_state = 'blocked')          as was_blocked,
  cs.blocked_reason,
  -- ここから追加。ISO の 1=月曜
  extract(isodow from (cs.started_at at time zone t.timezone))::int as local_weekday
from call_sessions cs
join tenants t on t.id = cs.tenant_id
left join disposition_codes dc on dc.code = cs.disposition_code;

-- ★ 必ず付け直す。create or replace view で reloptions が落ちると、
--   ビューは定義者の権限で走り、RLS を素通りして他テナントの数字が見える。
--   例外は出ず、数字が少し大きくなるだけなので、気付けない種類の穴になる。
alter view kpi_call_facts set (security_invoker = true);

grant select on kpi_call_facts to kaden_app;
