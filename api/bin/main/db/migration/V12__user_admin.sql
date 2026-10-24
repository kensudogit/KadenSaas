-- 管理者がユーザーを追加できるようにする。
--
-- ★ 初期パスワードはシステムが生成し、一度だけ画面に出す。
--   管理者に考えさせると「Password1」のような値が使われ、しかも
--   複数のユーザーで使い回される。生成した値は保存せず、
--   ハッシュだけを users.password_hash に入れる。
--
-- ★ 初期パスワードのまま使い続けられる状態を作らない。
--   発行した時点で password_change_required を立て、本人が変更するまで
--   画面が変更を促す。管理者が知っているパスワードのまま、
--   その人の名前で架電の記録が残るのは避けたい。

alter table users
  add column password_change_required boolean not null default false;

-- ★ 誰がいつ発行・変更したかは audit_logs に残る（users には持たせない）。
--   ここに last_password_changed_at のような列を足すと、
--   監査の出所が 2 つになる。

comment on column users.password_change_required is
  '管理者が発行した初期パスワードのままかどうか。本人が変更すると false になる';
