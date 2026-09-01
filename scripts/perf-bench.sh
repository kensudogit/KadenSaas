#!/bin/sh
# ============================================================================
# 実用規模での性能計測。
#
# ★ このスクリプトが存在する理由。
#   デモデータは通話 51 件しかない。その規模では、全走査でも索引走査でも
#   数ミリ秒で返るので、**遅くなる書き方をしても気付けない**。
#   実際、集計の絞り込みを local_date（式）で書いていたため、
#   すべての KPI・分析・履歴が call_sessions の全走査になっていた。
#   通話 51 件では 3ms、40 万件では 400ms。デモを触っている限り見えない。
#
#   さらに悪いことに、全走査は他テナントの行も読んでから RLS で捨てる。
#   1 社のダッシュボードの重さが**基盤全体の通話量**で決まるので、
#   本番で最初に踏むのは「自社の通話は少ないのに遅い」テナントになる。
#
# ★ 使い方。合成データを入れて測り、最後に消す。
#   既存のテナント（demo / other）には触れない専用テナントを作る。
#
#     sh scripts/perf-bench.sh              # 投入 → 計測 → 削除
#     sh scripts/perf-bench.sh --keep       # 削除しない（EXPLAIN を追いたいとき）
#     sh scripts/perf-bench.sh --clean      # 削除だけ
#
# ★ 数字そのものより「実行計画が Seq Scan かどうか」を見ること。
#   計測値は機械に依存するが、全走査に落ちているかどうかは依存しない。
#
# 前提: docker compose up 済み
# ============================================================================

set -e

DB_CONTAINER="${DB_CONTAINER:-kadensaas-db-1}"
API="${API_BASE:-http://localhost:8080}"
TENANT_ID="99999999-9999-9999-9999-999999999999"
SLUG="perfco"

# ★ 通話の件数。1 テナント・13 か月ぶんを想定した「小さめの実運用」。
#   担当者 20 人が 1 日 100 件かければ 1 年で 50 万件になるので、
#   40 万件は珍しい規模ではない。
CALLS="${PERF_CALLS:-400000}"
CUSTOMERS="${PERF_CUSTOMERS:-60000}"

psql() { docker exec -i "$DB_CONTAINER" psql -U postgres -d kaden "$@"; }

clean() {
  echo "合成データを削除します（テナント $SLUG）"
  # ★ tenants からの cascade で全部消える。個別に消すと消し漏れる
  psql -q -c "delete from tenants where id = '$TENANT_ID';"
  psql -q -c "analyze;"
  echo "削除しました"
}

case "$1" in
  --clean) clean; exit 0 ;;
esac

# ---------------------------------------------------------------- 投入

echo "合成データを投入します（通話 $CALLS 件 / 顧客 $CUSTOMERS 件）"
echo "  ★ demo / other には触れません。専用テナント $SLUG を作ります"

psql -q -v ON_ERROR_STOP=1 -v calls="$CALLS" -v customers="$CUSTOMERS" <<'SQL'
delete from tenants where id = '99999999-9999-9999-9999-999999999999';

insert into tenants (id, name, slug, timezone)
values ('99999999-9999-9999-9999-999999999999', '負荷計測商事', 'perfco', 'Asia/Tokyo');

insert into tenant_telephony (tenant_id, caller_id, dialing_enabled, recording_enabled)
values ('99999999-9999-9999-9999-999999999999', '+815099990000', true, true);

-- ★ 担当者。パスワードは demo の manager から借りる（計測用の login のため）。
--   ハッシュを直接書くと、seed-dev.sql を変えたときに食い違う
insert into users (id, tenant_id, email, password_hash, display_name, role, status)
select ('af000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,
       '99999999-9999-9999-9999-999999999999',
       case when g = 1 then 'manager@perf.example' else 'op' || g || '@perf.example' end,
       (select password_hash from users where email = 'manager@demo.example'),
       'オペ' || g,
       case when g <= 2 then 'manager' else 'operator' end, 'active'
from generate_series(1, 20) g;

insert into customers (id, tenant_id, company_name, contact_name, status)
select ('cf000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,
       '99999999-9999-9999-9999-999999999999',
       '株式会社サンプル' || g, '担当' || g,
       (array['new','working','won','lost'])[1 + (g % 4)]
from generate_series(1, :customers) g;

insert into customer_phones (id, tenant_id, customer_id, raw_number, e164, is_primary)
select ('df000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,
       '99999999-9999-9999-9999-999999999999',
       ('cf000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,
       '03-9999-' || lpad(g::text, 4, '0'),
       '+8139' || lpad(g::text, 8, '0'), true
from generate_series(1, :customers) g;

insert into campaigns (id, tenant_id, name, status)
values ('ef000000-0000-0000-0000-000000000001',
        '99999999-9999-9999-9999-999999999999', '負荷計測キャンペーン', 'running');

-- ★ コールリスト。7 件に 1 件は「まだかけない」（next_attempt_at が未来）にして、
--   キュー予約が索引の順序をそのまま使えているかを見えるようにする
insert into call_targets
  (tenant_id, campaign_id, customer_id, phone_id, priority, state, next_attempt_at)
select '99999999-9999-9999-9999-999999999999',
       'ef000000-0000-0000-0000-000000000001',
       ('cf000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,
       ('df000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,
       100 + (g % 5),
       case when g % 100 = 0 then 'done' else 'pending' end,
       case when g % 7 = 0 then now() + interval '2 days' else null end
from generate_series(1, :customers) g;

-- ★ 通話。13 か月ぶんを 9-20 時に散らす。
--   すべて終端状態にする（call_sessions_inflight_uniq は
--   「同じ番号への進行中の通話は 1 本」なので、途中状態だと衝突する）
insert into call_sessions
  (tenant_id, campaign_id, customer_id, operator_id, provider, direction,
   from_e164, to_e164, dial_state, disposition_code, blocked_reason,
   started_at, answered_at, ended_at, duration_seconds)
select '99999999-9999-9999-9999-999999999999',
       'ef000000-0000-0000-0000-000000000001',
       ('cf000000-0000-0000-0000-' || lpad((1 + g % :customers)::text, 12, '0'))::uuid,
       ('af000000-0000-0000-0000-' || lpad((1 + g % 20)::text, 12, '0'))::uuid,
       'twilio', 'outbound', '+815099990000',
       '+8139' || lpad((1 + g % :customers)::text, 8, '0'),
       st.state,
       case when st.state = 'blocked' then null
            else (array['CONNECTED','NO_ANSWER','BUSY',
                        'APPOINTMENT','NOT_INTERESTED','INVALID_NUMBER'])[1 + (g % 6)] end,
       case when st.state = 'blocked'
            then (array['do_not_call','outside_hours',
                        'max_attempts_per_day','holiday'])[1 + (g % 4)] end,
       ts.started,
       case when st.state = 'completed' then ts.started + interval '12 seconds' end,
       ts.started + interval '90 seconds',
       case when st.state = 'completed' then 30 + (g % 400) else 0 end
from generate_series(1, :calls) g
cross join lateral (
  select (now() - make_interval(days => (g % 365))
                - make_interval(secs => (g * 97) % 39600)
                + interval '9 hours') as started
) ts
cross join lateral (
  select case when g % 11 = 0 then 'blocked'
              when g % 7  = 0 then 'no_answer'
              when g % 13 = 0 then 'busy'
              when g % 29 = 0 then 'failed'
              else 'completed' end as state
) st;

analyze;
SQL

echo "投入しました"
echo ""

# ---------------------------------------------------------------- 実行計画

echo "実行計画（Seq Scan on call_sessions が出たら全走査に落ちている）"
echo "----------------------------------------------------------------------"
psql -q <<'SQL' 2>&1 | grep -E '^###|Execution Time|Seq Scan on call_|Index Scan using call_'
set role kaden_app;
begin;
select set_config('app.tenant_id', '99999999-9999-9999-9999-999999999999', true);

\echo '### 架電履歴の総件数'
explain (analyze, costs off)
select count(*) from call_sessions cs
 where cs.started_at >= app_tenant_day_start(current_date - 29)
   and cs.started_at <  app_tenant_day_start(current_date + 1);

\echo '### KPI 時間帯別'
explain (analyze, costs off)
select local_hour, count(*) filter (where counts_in_denominator) as denominator
  from kpi_call_facts
 where started_at >= app_tenant_day_start(current_date - 29)
   and started_at <  app_tenant_day_start(current_date + 1)
 group by local_hour;

\echo '### キュー予約（次の 1 件を受け取る）'
explain (analyze, costs off)
select t.id from call_targets t
 where t.campaign_id = 'ef000000-0000-0000-0000-000000000001'
   and t.state = 'pending'
   and (t.next_attempt_at is null or t.next_attempt_at <= now())
 order by t.priority, t.next_attempt_at nulls first
 limit 1 for update skip locked;
rollback;
SQL
echo ""

# ---------------------------------------------------------------- HTTP

TOKEN=$(curl -s -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"tenantSlug":"perfco","email":"manager@perf.example","password":"password"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

if [ -z "$TOKEN" ]; then
  echo "★ ログインできませんでした。api が起動しているか確認してください"
  echo "  （seed-dev.sql の manager@demo.example が居ることも前提です）"
  exit 1
fi

echo "HTTP のレイテンシ（5 回の平均）"
echo "----------------------------------------------------------------------"
bench() {
  curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" "$API$2"   # 暖機
  t=0
  i=0
  while [ $i -lt 5 ]; do
    s=$(curl -s -o /dev/null -w '%{time_total}' -H "Authorization: Bearer $TOKEN" "$API$2")
    t=$(awk "BEGIN{print $t + $s * 1000}")
    i=$((i + 1))
  done
  awk "BEGIN{printf \"  %-34s %8.1f ms\n\", \"$1\", $t / 5}"
}
bench "GET /call-history"       "/api/v1/call-history?limit=50"
bench "GET /kpi/summary"        "/api/v1/kpi/summary"
bench "GET /kpi/hourly"         "/api/v1/kpi/hourly"
bench "GET /analytics/operator" "/api/v1/analytics/operator"
bench "GET /analytics/hourly"   "/api/v1/analytics/hourly"
echo ""

if [ "$1" = "--keep" ]; then
  echo "合成データを残しました。消すときは:"
  echo "  sh scripts/perf-bench.sh --clean"
else
  clean
fi
