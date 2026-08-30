-- ============================================================================
-- 開発用のデモデータ。
--
-- ★ 本番では絶対に流さない。パスワードが既知の値になっている。
--   流すのは docker-compose のローカル環境だけ。
--
-- ★ 意図的に「関門に止められる相手」を入れてある。
--   正常系だけのデータだと、DNC や時間帯の判定が効いているかを
--   画面から確認できない。止まるところまで含めてデモになる。
-- ============================================================================

\set ON_ERROR_STOP on

-- パスワードはすべて "password"（BCrypt cost 10）
-- ★ このハッシュは bcrypt で実際に生成して検証したもの。
--   ネット上でよく見る「password のハッシュ」を貼ると、
--   一致しないまま「認証が壊れている」と誤診することになる（実際にやった）。
\set pw '''$2a$10$zcnZlk.6h5nLp0pXgKxaM.ZPRmPWpZ/CODwRt7i6Hjqzu3.Dp7O3e'''

-- ---------------------------------------------------------------- テナント

insert into tenants (id, name, slug, timezone, calling_hours_start, calling_hours_end)
values ('11111111-1111-1111-1111-111111111111', 'デモ商事', 'demo', 'Asia/Tokyo',
        '09:00', '20:00')
on conflict (id) do nothing;

-- ★ 2 つ目のテナント。テナント分離が効いていることを画面から
--   確かめられるようにするため、必ず 2 社入れる
insert into tenants (id, name, slug)
values ('22222222-2222-2222-2222-222222222222', '別会社', 'other')
on conflict (id) do nothing;

insert into tenant_telephony (tenant_id, caller_id, recording_enabled)
values ('11111111-1111-1111-1111-111111111111', '+81312340000', true)
on conflict (tenant_id) do nothing;

-- ---------------------------------------------------------------- 利用者

insert into users (id, tenant_id, email, password_hash, display_name, role)
values
  ('aaaaaaaa-0000-0000-0000-000000000001',
   '11111111-1111-1111-1111-111111111111',
   'operator@demo.example', :pw, '架電 太郎', 'operator'),
  ('aaaaaaaa-0000-0000-0000-000000000002',
   '11111111-1111-1111-1111-111111111111',
   'manager@demo.example', :pw, '管理 花子', 'manager'),
  ('bbbbbbbb-0000-0000-0000-000000000001',
   '22222222-2222-2222-2222-222222222222',
   'operator@other.example', :pw, '別社 次郎', 'operator')
on conflict (id) do nothing;

-- ---------------------------------------------------------------- 顧客

insert into customers (id, tenant_id, company_name, contact_name, status, owner_id, note)
values
  ('c0000000-0000-0000-0000-000000000001',
   '11111111-1111-1111-1111-111111111111',
   '株式会社アルファ', '田中', 'new',
   'aaaaaaaa-0000-0000-0000-000000000001',
   '前回は資料送付のみ。料金面に関心あり'),
  ('c0000000-0000-0000-0000-000000000002',
   '11111111-1111-1111-1111-111111111111',
   '株式会社ブラボー', '佐藤', 'new',
   'aaaaaaaa-0000-0000-0000-000000000001', null),
  -- ★ この会社は DNC に入れてある。関門が止めることを確認するため
  ('c0000000-0000-0000-0000-000000000003',
   '11111111-1111-1111-1111-111111111111',
   '株式会社チャーリー', '鈴木', 'new',
   'aaaaaaaa-0000-0000-0000-000000000001', null),
  ('c0000000-0000-0000-0000-000000000009',
   '22222222-2222-2222-2222-222222222222',
   '他社の顧客', '見えてはいけない', 'new', null, null)
on conflict (id) do nothing;

insert into customer_phones (tenant_id, customer_id, raw_number, e164, kind, is_primary)
values
  ('11111111-1111-1111-1111-111111111111',
   'c0000000-0000-0000-0000-000000000001', '03-1234-0001', '+81312340001', 'main', true),
  ('11111111-1111-1111-1111-111111111111',
   'c0000000-0000-0000-0000-000000000002', '03-1234-0002', '+81312340002', 'main', true),
  ('11111111-1111-1111-1111-111111111111',
   'c0000000-0000-0000-0000-000000000003', '03-1234-0003', '+81312340003', 'main', true),
  ('22222222-2222-2222-2222-222222222222',
   'c0000000-0000-0000-0000-000000000009', '03-9999-9999', '+81399999999', 'main', true)
on conflict do nothing;

-- ★ 関門に止められる相手。画面で「正しく止まる」ことを見せるため
insert into do_not_call_entries (tenant_id, e164, reason, source)
values ('11111111-1111-1111-1111-111111111111', '+81312340003',
        '電話口で再勧誘を断られた', 'customer_request')
on conflict do nothing;

-- ---------------------------------------------------------------- キャンペーン

insert into campaigns (id, tenant_id, name, status, script)
values ('cccccccc-0000-0000-0000-000000000001',
        '11111111-1111-1111-1111-111111111111',
        '2026年秋 新規開拓', 'running',
        E'お世話になっております。デモ商事の○○と申します。\n'
        '本日は、通信費の見直しについてご案内のお電話です。')
on conflict (id) do nothing;

insert into call_targets (tenant_id, campaign_id, customer_id, phone_id, priority, state)
select '11111111-1111-1111-1111-111111111111',
       'cccccccc-0000-0000-0000-000000000001',
       p.customer_id, p.id,
       case when p.e164 = '+81312340001' then 10 else 100 end,
       'pending'
  from customer_phones p
 where p.tenant_id = '11111111-1111-1111-1111-111111111111'
on conflict do nothing;

\echo ''
\echo 'デモデータを投入しました。'
\echo '  テナント : demo（デモ商事）'
\echo '  担当者   : operator@demo.example / password'
\echo '  管理者   : manager@demo.example / password'
\echo '  キャンペーン ID: cccccccc-0000-0000-0000-000000000001'
\echo ''
\echo '★ 株式会社チャーリー（03-1234-0003）は DNC に入れてあります。'
\echo '  発信しようとすると関門が止めます。それが正常な動作です。'
