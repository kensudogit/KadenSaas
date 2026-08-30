-- ============================================================================
-- CallSid からテナントを引くための、極小の RLS 迂回。
--
-- ★ なぜ必要か。Twilio の webhook は「どのテナントの通話か」を知らない状態で
--   届く。署名は検証できるが、それだけではテナントが決まらない。
--   一方 call_sessions は RLS の対象なので、テナントを設定しないと 1 行も引けない。
--   鶏卵になる。
--
-- ★ なぜ関数にするのか。アプリロールに BYPASSRLS を与えれば解決するが、
--   それはテナント分離を丸ごと捨てるのと同じ。ここだけを security definer に
--   して、返す列を (tenant_id, id) の 2 つに絞る。
--   これなら「CallSid を知っている人が、その通話の所属テナントを知る」以上のことは
--   できない。CallSid は Twilio が発行する推測不能な識別子で、
--   署名検証を通ったリクエストしかここに到達しない。
--
-- ★ 絶対にやってはいけない拡張:
--     - 顧客名や電話番号を返り値に足す
--     - CallSid 以外（tenant_id や日付）で引けるようにする
--     - 一覧を返せるようにする
--   どれも「1 件の所属を知る」を超えて、他テナントの中身を読む道具になる。
-- ============================================================================

create or replace function call_sessions_lookup(p_call_sid text)
  returns table (tenant_id uuid, id uuid)
  language sql
  stable
  -- ★ 定義者（migrator）の権限で実行する。これが RLS を越える唯一の経路
  security definer
  -- ★ search_path を固定する。固定しないと、呼び出し側が自分のスキーマに
  --   偽の call_sessions を作って、この関数にそれを読ませることができる
  set search_path = public
  as $$
    select cs.tenant_id, cs.id
      from call_sessions cs
     where cs.provider_call_sid = p_call_sid
     limit 1
  $$;

revoke all on function call_sessions_lookup(text) from public;
grant execute on function call_sessions_lookup(text) to kaden_app;

comment on function call_sessions_lookup(text) is
  'Twilio webhook 専用。CallSid から所属テナントだけを引く。'
  '返す列を増やさないこと（RLS を越える唯一の経路のため）。';

-- ★ Media Stream 用。こちらは call_session_id から所属テナントを引く。
--   Twilio の customParameters で受け取った id しか手掛かりが無いため、
--   webhook と同じ理由で必要になる。
--   返す列は 1 つだけ。CallSid 版と同じく、増やさないこと。
create or replace function call_sessions_lookup_by_id(p_id uuid)
  returns table (tenant_id uuid)
  language sql
  stable
  security definer
  set search_path = public
  as $$
    select cs.tenant_id from call_sessions cs where cs.id = p_id limit 1
  $$;

revoke all on function call_sessions_lookup_by_id(uuid) from public;
grant execute on function call_sessions_lookup_by_id(uuid) to kaden_app;

comment on function call_sessions_lookup_by_id(uuid) is
  'Media Stream 専用。call_session_id から所属テナントだけを引く。';
