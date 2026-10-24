-- ============================================================================
-- 外部キーの「参照している側」に索引を張る。
--
-- ★ PostgreSQL は外部キーの**参照される側**（親の主キー）には索引を要求するが、
--   **参照する側**（子の列）には自動で索引を作らない。作らなくても
--   INSERT / SELECT は正常に動くので、抜けていても気付かない。
--
--   気付くのは親を消したときだけ。親の 1 行を消すたびに、
--   PostgreSQL は子テーブルを丸ごと走査して参照が残っていないか確かめる。
--   親を N 行消せば、子の全走査が N 回。
--
-- ★ 実測（通話 40 万件・コールリスト 6 万件のテナント）。
--   call_targets を 200 行消すだけで:
--
--       索引なし   11,123 ms    （1 行あたり 55ms。call_sessions を毎回全走査）
--       索引あり        6.2 ms    （1,787 倍）
--
--   キャンペーン 1 本（コールリスト 6 万件）の削除に換算すると
--   55 分 → 2 秒。テナントの削除（退会）に至っては、
--   索引が無い状態では 11 分待っても終わらなかった。
--   索引を張ったあとは 12 秒で完了する。
--
--   ★ この壊れ方が厄介なのは、遅いだけでなく**行ロックを取ったまま**
--     待たせること。退会処理のつもりで打った 1 行の delete が、
--     その間ずっと call_sessions への書き込みと競合する。
--     つまり「1 社を消そうとしたら、全社の架電が止まる」。
--
-- ★ どの外部キーに索引が要るかは SQL で機械的に出せる。
--   新しい外部キーを足したときは、これを流して抜けを確かめる:
--
--     select c.conrelid::regclass as child, c.conname, c.confrelid::regclass as parent
--       from pg_constraint c
--      where c.contype = 'f' and c.connamespace = 'public'::regnamespace
--        and not exists (
--          select 1 from pg_index i
--           where i.indrelid = c.conrelid
--             and (string_to_array(i.indkey::text,' ')::smallint[])[1:array_length(c.conkey,1)]
--                 = c.conkey);
--
-- ★ すべてに張るわけではない。索引は書き込みのたびに更新されるので、
--   call_sessions のように毎通話 INSERT される表では 1 本の重みが違う。
--   ここで足すのは「親が実際に削除される経路がある」ものだけに絞る。
--
--     tenants    退会。すべてに cascade する
--     campaigns  キャンペーンの削除。call_targets へ cascade、call_sessions は set null
--     customers  顧客の削除。call_targets / callbacks へ cascade
--
--   users は削除しない（status で無効化する）ので、users を親とする
--   外部キーはここでは張らない。削除する運用に変えるなら足すこと。
-- ============================================================================

-- ★ 部分索引にする。call_target_id / campaign_id が null の行
--   （画面から番号を直接指定した発信など）は外部キーの検査対象にならないので、
--   索引に載せる必要がない。call_sessions はこのシステムでいちばん
--   書き込みが多い表なので、載せる行を減らせるなら減らす。
create index if not exists call_sessions_target_fk_idx
  on call_sessions (tenant_id, call_target_id)
  where call_target_id is not null;

create index if not exists call_sessions_campaign_fk_idx
  on call_sessions (tenant_id, campaign_id)
  where campaign_id is not null;

-- ★ 顧客の削除で cascade する側。customers は 1 件ずつ消される運用がある
--   （誤って取り込んだリストの削除、本人からの削除依頼）
create index if not exists call_targets_customer_fk_idx
  on call_targets (tenant_id, customer_id);

create index if not exists callbacks_customer_fk_idx
  on callbacks (tenant_id, customer_id);

comment on index call_sessions_target_fk_idx is
  '外部キー call_sessions_target_fk の検査用。'
  'これが無いと call_targets を 1 行消すたびに call_sessions を全走査する'
  '（実測 55ms/行。キャンペーン 1 本の削除で 55 分）。';
