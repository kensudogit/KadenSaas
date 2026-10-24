-- ============================================================================
-- 集計とキュー予約の実行計画を直す。
--
-- ★ 計測して分かったこと（通話 40 万件・1 テナント・13 か月ぶんで実測）:
--
--     GET /api/v1/kpi/summary        304 ms   call_sessions を全走査
--     GET /api/v1/kpi/hourly         482 ms   全走査 + 一時ファイル 16MB
--     GET /api/v1/analytics/operator 310 ms   全走査
--     GET /api/v1/call-history        272 ms   count(*) が全走査
--
--   原因は 1 つ。集計の絞り込みが local_date、すなわち
--   (started_at at time zone t.timezone)::date という**式**に対して行われている。
--   式には索引が無く、しかも timezone は tenants との結合先にあるので、
--   プランナは「まず全件読んでから式を評価する」しか選べない。
--
--   ★ ここが多テナントで効いてくる。全走査は他テナントの行も読んでから
--     RLS で捨てるので、1 社のダッシュボードの重さが**基盤全体の通話量**で
--     決まる。自社の通話が 100 件でも、隣の会社が 1000 万件持っていれば遅い。
--     行を増やしたのは自分ではないので、原因にたどり着けない。
--
-- ★ 直し方は「式で絞るのをやめ、started_at の範囲で絞る」。
--   テナントのローカル日付 D は、そのテナントのタイムゾーンでの
--   D の 0 時という**絶対時刻**に一対一で対応する。範囲に直せば
--   call_sessions_tenant_started_idx (tenant_id, started_at desc) がそのまま効く。
--
--   境界の作り方だけを app_tenant_day_start() に閉じ込める。
--   各クエリに (d::timestamp at time zone tz) を書き散らすと、
--   1 箇所書き間違えたときに「集計が 1 日ずれる」という形で静かに壊れる。
-- ============================================================================

-- ---------------------------------------------------------------- 日境界

-- ★ stable。1 回のクエリの中で値が変わらないので、プランナは
--   実行開始時に一度だけ評価し、索引走査の境界値として使える。
--   immutable にはできない（tenants の timezone を読むため）。
--   volatile にすると行ごとに評価され、索引が使われなくなる。
create or replace function app_tenant_day_start(d date)
  returns timestamptz
  language sql
  stable
  as $$
    select (d::timestamp) at time zone t.timezone
      from tenants t
     where t.id = app_current_tenant()
  $$;

comment on function app_tenant_day_start(date) is
  'テナントのローカル日付 D の 0 時を絶対時刻で返す。'
  '集計の絞り込みを local_date（式）から started_at（索引付き）へ移すために使う。'
  '「D から E まで」は  started_at >= app_tenant_day_start(D) '
  'and started_at < app_tenant_day_start(E + 1)  と書く（終端は開区間）。';

grant execute on function app_tenant_day_start(date) to kaden_app;

-- ---------------------------------------------------------------- キュー予約

-- ★ 「次の 1 件を受け取る」が 10ms かかり、索引を 9,772 行ぶん読んでいた。
--   1 行しか要らないのに全部読むのは、索引の並びと order by の並びが
--   食い違っていたため。
--
--     索引     ... priority, next_attempt_at        （= ASC NULLS LAST）
--     クエリ   ... priority, next_attempt_at nulls first
--
--   NULL の位置が逆なので索引の順序をそのまま使えず、
--   priority が同じ塊（約 1 万行）を全部読んで並べ替えてから 1 行返していた。
--   コールリストが大きいほど重くなる。担当者が最も頻繁に押す操作なので、
--   ここが伸びると人数ぶん待ち時間が積まれる。
--
--   next_attempt_at が NULL＝「いつでもかけてよい」で、最優先で配りたい。
--   索引側を nulls first に揃えれば、最初の 1 行で止まる。
drop index if exists call_targets_queue_idx;
create index call_targets_queue_idx
  on call_targets (tenant_id, campaign_id, priority, next_attempt_at nulls first)
  where state = 'pending';

comment on index call_targets_queue_idx is
  'CallQueue.reserveNext 専用。order by priority, next_attempt_at nulls first と'
  '並びを一致させてある。nulls first を外すと、1 行取るために'
  '同 priority の塊を全部読んで並べ替えるようになる（実測 10ms / 9,772 行）。';
