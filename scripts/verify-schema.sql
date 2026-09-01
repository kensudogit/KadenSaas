-- ============================================================================
-- スキーマの「効いていること」を確かめる。
--
-- ★ ポリシーが書いてあることと、効いていることは別。
--   force row level security を忘れる／接続ロールが superuser、
--   のどちらでもポリシーは静かに素通りする。壊れ方が「本番でだけ漏れる」
--   なので、CI で毎回ここを通す。
-- ============================================================================

\set ON_ERROR_STOP on

-- 前準備は migrator（RLS 迂回）で行う
insert into tenants (id, name, slug) values
  ('11111111-1111-1111-1111-111111111111', 'テナントA', 'tenant-a'),
  ('22222222-2222-2222-2222-222222222222', 'テナントB', 'tenant-b');

insert into users (id, tenant_id, email, password_hash, display_name, role) values
  ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
   'a@example.com', 'x', 'A担当', 'operator'),
  ('bbbbbbbb-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222',
   'b@example.com', 'x', 'B担当', 'operator');

insert into customers (id, tenant_id, company_name) values
  ('aaaaaaaa-0000-0000-0000-000000000010', '11111111-1111-1111-1111-111111111111', 'A社の顧客'),
  ('bbbbbbbb-0000-0000-0000-000000000010', '22222222-2222-2222-2222-222222222222', 'B社の顧客');

\echo ''
\echo '### 1. テナント分離（A で接続したら A の行しか見えない）'
begin;
set local role kaden_app;
set local app.tenant_id = '11111111-1111-1111-1111-111111111111';
select count(*) as "A から見える customers（期待 1）" from customers;
select count(*) as "A から見える B の行（期待 0）" from customers
  where tenant_id = '22222222-2222-2222-2222-222222222222';
commit;

\echo ''
\echo '### 2. tenant_id を偽って書けないこと（WITH CHECK）'
begin;
set local role kaden_app;
set local app.tenant_id = '11111111-1111-1111-1111-111111111111';
do $$
begin
  insert into customers (tenant_id, company_name)
  values ('22222222-2222-2222-2222-222222222222', '他テナントへの書き込み');
  raise exception '!! 失敗: 他テナントの行を挿入できてしまった';
exception
  when insufficient_privilege then
    raise notice 'OK: 他テナントへの insert は拒否された';
end $$;
commit;

\echo ''
\echo '### 3. tenant_id 未設定なら 1 行も見えないこと（fail closed）'
begin;
set local role kaden_app;
-- app.tenant_id をわざと入れない
select count(*) as "未設定で見える件数（期待 0）" from customers;
commit;

\echo ''
\echo '### 4. 二重発信の防止（同じ番号への進行中の通話は 1 本だけ）'
insert into call_sessions (tenant_id, customer_id, from_e164, to_e164, dial_state)
values ('11111111-1111-1111-1111-111111111111',
        'aaaaaaaa-0000-0000-0000-000000000010', '+81300000000', '+81312345678', 'dialing');

do $$
begin
  insert into call_sessions (tenant_id, customer_id, from_e164, to_e164, dial_state)
  values ('11111111-1111-1111-1111-111111111111',
          'aaaaaaaa-0000-0000-0000-000000000010', '+81300000000', '+81312345678', 'queued');
  raise exception '!! 失敗: 同じ番号に 2 本目を発信できてしまった';
exception
  when unique_violation then
    raise notice 'OK: 2 本目の発信は DB に拒否された';
end $$;

\echo ''
\echo '### 5. 通話が終われば同じ番号に再度かけられること'
update call_sessions set dial_state = 'completed', ended_at = now()
 where to_e164 = '+81312345678';
insert into call_sessions (tenant_id, customer_id, from_e164, to_e164, dial_state)
values ('11111111-1111-1111-1111-111111111111',
        'aaaaaaaa-0000-0000-0000-000000000010', '+81300000000', '+81312345678', 'queued');
select count(*) as "同じ番号への通話履歴（期待 2）" from call_sessions
  where to_e164 = '+81312345678';

\echo ''
\echo '### 6. 状態が巻き戻らないこと（単調前進）'
-- completed のあとに ringing が遅れて届いた、という状況を作る
update call_sessions set dial_state = 'completed', ended_at = now()
 where to_e164 = '+81312345678' and dial_state = 'queued';

-- ★ アプリはこの where を必ず付ける。付けないと巻き戻る
update call_sessions
   set dial_state = 'ringing'
 where to_e164 = '+81312345678'
   and dial_state_rank < call_dial_state_rank('ringing');

select count(*) as "ringing に巻き戻った行（期待 0）" from call_sessions
  where to_e164 = '+81312345678' and dial_state = 'ringing';

\echo ''
\echo '### 7. blocked には理由が必須'
do $$
begin
  insert into call_sessions (tenant_id, customer_id, from_e164, to_e164, dial_state)
  values ('11111111-1111-1111-1111-111111111111',
          'aaaaaaaa-0000-0000-0000-000000000010', '+81300000000', '+81399999999', 'blocked');
  raise exception '!! 失敗: 理由なしで blocked を作れてしまった';
exception
  when check_violation then
    raise notice 'OK: 理由のない blocked は拒否された';
end $$;

\echo ''
\echo '### 8. 監査ログを消せないこと'
insert into audit_logs (tenant_id, user_id, action)
values ('11111111-1111-1111-1111-111111111111',
        'aaaaaaaa-0000-0000-0000-000000000001', 'test.action');
begin;
set local role kaden_app;
set local app.tenant_id = '11111111-1111-1111-1111-111111111111';
do $$
begin
  delete from audit_logs;
  raise exception '!! 失敗: 監査ログを消せてしまった';
exception
  when insufficient_privilege then
    raise notice 'OK: 監査ログの削除は拒否された';
end $$;
commit;

\echo ''
\echo '### 9. KPI ビューがテナントを越えないこと'
begin;
set local role kaden_app;
set local app.tenant_id = '11111111-1111-1111-1111-111111111111';
select count(*) as "A から見える通話ファクト（期待 2、B は含まない）" from kpi_call_facts;
commit;

\echo ''
\echo '### 10. 削除で全走査になる外部キーが無いこと'
-- ★ PostgreSQL は外部キーの「参照する側」に索引を作らない。
--   抜けていても INSERT / SELECT は正常に動くので気付かない。
--   気付くのは親を消したときで、親 1 行につき子を丸ごと走査する。
--   実測では call_targets を 200 行消すのに 11 秒かかった（索引ありは 6ms）。
--   キャンペーン 1 本の削除で 55 分、テナントの削除は終わらない。
--
--   ★ そのあいだ行ロックを持ち続けるので、1 社を消そうとすると
--     全社の架電が止まる。遅いだけでは済まない。
--
--   users を親とするものは除く（削除せず status で無効化する運用のため）。
--   users を実際に削除する運用に変えるなら、この除外も外すこと。
--
-- ★ 判定の仕方。列の並びの完全一致は要求しない。要求すると、
--   実害の無いものまで挙がって「毎回 1 件出るので誰も見ない」検査になる
--   （call_metrics の (tenant_id, call_session_id) は、主キーの
--   (call_session_id) だけで索引走査でき、tenant_id は絞り込みで済む）。
--
--   逆に「先頭列が外部キーの列のどれか」まで緩めると、何も検知しなくなる。
--   このスキーマの外部キーはすべて tenant_id で始まり、tenant_id 先頭の索引は
--   どの表にもあるからで、それは「そのテナントの行を全部読む」план でしかない。
--
--   使えるのは「索引の先頭が、その外部キーの列だけで構成されていて、かつ
--   tenant_id 以外の列を含む」場合。tenant_id 以外が入って初めて絞り込める。
--
--   tenant_id 1 列だけの外部キー（... -> tenants）は除く。
--   テナントは 1 行しか消さないので走査も 1 回で、全テーブルが該当してしまう。
select coalesce(string_agg(child || ' -> ' || parent, ' / '), '（無し）')
         as "索引で辿れない外部キー（期待 無し）"
  from (
    select c.conrelid::regclass::text as child,
           c.confrelid::regclass::text as parent
      from pg_constraint c
      join pg_attribute ta
        on ta.attrelid = c.conrelid and ta.attname = 'tenant_id'
     where c.contype = 'f'
       and c.connamespace = 'public'::regnamespace
       and c.confrelid::regclass::text in ('campaigns', 'customers',
                                           'call_targets', 'call_sessions')
       -- tenant_id 単独の外部キーは対象外
       and c.conkey <> array[ta.attnum]
       and not exists (
         select 1
           from pg_index i
           cross join lateral (
             select (string_to_array(i.indkey::text, ' ')::smallint[])
                      [1:least(array_length(c.conkey, 1),
                               array_length(string_to_array(i.indkey::text, ' '), 1))]
               as lead
           ) p
          where i.indrelid = c.conrelid
            -- 先頭列がすべてこの外部キーの列であること
            and c.conkey @> p.lead
            -- かつ tenant_id 以外を含むこと（tenant_id だけでは絞れない）
            and exists (select 1 from unnest(p.lead) x where x <> ta.attnum))
  ) missing;

\echo ''
\echo '=== 検証おわり ==='
