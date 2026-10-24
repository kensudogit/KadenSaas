-- 発信者番号が未設定でも、止めた発信を記録できるようにする。
--
-- ★ 関門は tenant_telephony が無いテナントを telephony_not_configured で止める。
--   ところが「止めた記録」を書く時点で from_e164 に入れるものが無く、
--   not null 制約で落ちていた。いちばん記録が要る場面（設定漏れで
--   1 件も鳴らない）で、その理由が 1 行も残らないことになる。
--
-- ★ not null は外すが、条件つきで残す。実際に鳴らす行（blocked 以外）は
--   発信者番号が無ければ Twilio に渡せないので、そこは DB で禁じたままにする。

alter table call_sessions alter column from_e164 drop not null;

alter table call_sessions
  add constraint call_sessions_from_required_unless_blocked
  check (dial_state = 'blocked' or from_e164 is not null);
