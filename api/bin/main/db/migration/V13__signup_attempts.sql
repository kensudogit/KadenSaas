-- 公開サインアップの濫用を抑えるための記録。
--
-- ★ 認証前のテーブルなので tenant_id を持たない。したがって RLS も付けない。
--   テナントが決まる前の出来事を記録する場所で、webhook_events と同じ扱い。
--
-- ★ なぜ要るか。誰でも登録できるようにすると、스크립트で 1 分間に数千の
--   テナントを作られる。作られたテナントは発信できない（発信者番号が
--   未設定なので関門が止める）が、識別子（slug）は先に取られてしまうし、
--   DB も膨らむ。IP ごとの回数で頭を押さえる。
--
-- ★ 失敗も記録する。成功だけ数えると、識別子の重複で弾かれ続ける
--   総当たりが素通りする。
--
-- ★ IP は個人情報になりうるので保持期間を短くする。
--   定期ジョブが古い行を消す。

create table signup_attempts (
  id          bigserial primary key,
  ip          inet,
  -- 何を取ろうとしたか。総当たりの形を後から見るために残す
  slug        text,
  succeeded   boolean not null,
  -- 失敗の理由（slug_taken / weak_password / rate_limited など）
  reason      text,
  created_at  timestamptz not null default now()
);

-- ★ 直近 N 分の件数を数えるための索引。この用途しか無いので複合で持つ
create index signup_attempts_ip_time_idx on signup_attempts (ip, created_at desc);
create index signup_attempts_time_idx on signup_attempts (created_at);

grant select, insert, delete on signup_attempts to kaden_app;
grant usage, select on sequence signup_attempts_id_seq to kaden_app;
