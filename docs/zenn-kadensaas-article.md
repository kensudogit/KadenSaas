---
title: "queued の行が無ければ鳴らせない — 架電SaaSの関門と RLS"
emoji: "☎️"
type: "tech"
topics: ["java", "python", "fastapi", "twilio", "postgresql"]
published: false
---

架電システムのバグは、画面の 500 ではなく相手の携帯として届きます。二重発信は2回鳴ることで、DNC 漏れは断った相手にもう一度かけることです。どちらもロールバックできません。

`KadenSaas` では「かけてよいか」は Spring の `DialingGate` だけが決めます。FastAPI の voice は Twilio の `calls.create` を `dialer.py` の1関数に限り、**`dial_state = queued` の行が既にあるときだけ**鳴らします。行が無ければ鳴らせません。関門を通らない「テスト発信」ボタンを後から足せない構造です。

この記事はその関門の実装メモです。営業電話の推奨ではありません。特商法・録音同意・DNC の適法性は判断していません。自動発信（プログレッシブ）は未実装です。

# 何を置いているか

| 層 | 役割 |
|---|---|
| api | Java / Spring Boot。スキーマ（Flyway）と関門と JWT 発行 |
| voice | Python / FastAPI。Twilio・Media Streams・録音 URL |
| web | Next.js。ソフトフォンと KPI |
| DB | PostgreSQL 16 + RLS（ホスト **5433**） |

api は Twilio SDK を入れません。voice はマイグレーションを持ちません。JWT の秘密は両方で同じ値です。片方だけ作り直すと「api は通るのに voice が 401」になります。

ローカルは `docker compose up -d` で db / redis / minio / api / voice / web です。アプリは `kaden_app` で繋ぎます。superuser だと RLS が素通りします。

# 関門を通った行だけが鳴る

`evaluate` は `Propagation.MANDATORY` です。トランザクションの外から呼ぶと落ちます。RLS 未設定のまま DNC を見ると 0 行になり、「拒否されていない」と判定してかけてしまいます。静かに間違えるより例外の方がよい、という選択です。

見る順はだいたい次です。

1. 全体スイッチ（`KADEN_DIALING_ENABLED`）
2. テナント別スイッチと発信者番号（行が無ければ `telephony_not_configured`）
3. DNC（最初に見る。後ろに置くと拒否の事実が残らない）
4. 時間帯・曜日・祝日
5. 回数上限
6. 二重発信（事前チェック。砦は部分ユニーク）

止めた発信は `blocked` と理由を残します。握りつぶすと「なぜ架電数が伸びないか」を説明できません。全体停止とテナント停止は両方要ります。1社の苦情で全テナントを止めたくないし、基盤事故でテナントの数だけトグルしたくないからです。

voice 側は状態を先に `queued → dialing` にしてから Twilio を呼びます。逆にすると、鳴っているのに記録が無い通話ができます。Twilio が失敗したら `failed` に倒せます。SDK は同期クライアントなので `asyncio.to_thread` に逃がします。イベントループ上で待つと、同時発信が増えたとき webhook が遅れ、再送で詰まります。

```sql
update call_sessions
   set dial_state = 'dialing'
 where id = $1 and dial_state = 'queued'
```

0 行なら「関門未通過」と「発信済み」を同じ拒否にします。どちらも鳴らしてはいけないからです。`client.calls.create` が `dialer.py` 以外に増えていないかは `scripts/check-boundaries.sh` で見ます。

# RLS は 0 行をエラーにしない

テナントは `SET LOCAL app.tenant_id` です。未設定なら全件見えるのではなく、1行も見えません。接続プールに値が残らないよう、`SET`（LOCAL なし）は使いません。

ここが一番踏みやすいです。例外が出ません。画面は「データがありません」だけです。派生クエリ（`findByXxx`）は `@Transactional` が無いと Spring のトランザクションに入らず、RLS が常に 0 件です。リポジトリは `TenantScopedRepository` を継承します。`JdbcTemplate` を使うクラスにも `@Transactional` を付けます。

signup はテナントがまだ無い状態で `users` に書きます。1つの `@Transactional` で両方書くと、users が RLS に弾かれます。テナント insert → `TenantContext.set` → 別 Bean の `REQUIRES_NEW` で user insert です。同じクラス内呼び出しはプロキシを通りません。

公開登録（`KADEN_SIGNUP_MODE=open`）でも、登録処理は `tenant_telephony` を作りません。直後は関門が止めます。ここが崩れると「誰でも迷惑電話をかけられる基盤」になります。`OpenSignupTest` が実際に発信を試して固定しています。IP 制限は事故止めであり、`X-Forwarded-For` を信じている以上、攻撃耐性だとは書いていません。CAPTCHA は未実装です。

マネージド Postgres の superuser でアプリを繋ぐと、`FORCE ROW LEVEL SECURITY` が効きません。起動時に接続ロールを検査し、本番では止めます。書いてあるのに効かない状態より、起動しない方がよいです。

# 通話は3列、状態は巻き戻さない

| 列 | 誰が書くか |
|---|---|
| `dial_state` | Twilio（機械） |
| `disposition_code` | 人 / AI（履歴が正本） |
| 録音・文字起こし | ジョブ |

1本の status に詰めると「通話は終わったが文字起こし中」が書けません。同一性は `provider_call_sid` の unique と upsert です。自前 UUID で先勝ち INSERT すると、statusCallback が先に着いたとき2行になります。

webhook は順不同です。遅れた `ringing` が `completed` を上書きしないよう、`dial_state_rank` 生成列で `rank < 新しい値` のときだけ更新します。弾いたイベントも `applied = false` で残します。

署名は `PUBLIC_BASE_URL` と Twilio が呼んだ URL を1文字も違えません。`request.url` から組み立てると、プロキシの後ろで http と内部 Host になります。全件 403 のときは末尾スラッシュを先に見ます。陽性対照の無い「署名なしで 403」テストだけでは、常に 403 の実装も通ります。

Media Streams は `voice-media` です。通常 API と同じワーカーに載せると、1通話が毎秒50フレームで API を食います。録音の署名 URL は voice だけが出し、5分で切れ、同じトランザクションでアクセスログを書きます。書けなければ URL も返しません。無いと見せないは同じ 404 です。

# KPI は率を返さない

定義は `kpi_call_facts` だけです。画面ごとに接続の条件を書かない。API は分子と分母を返し、画面が `32.4%（162 / 500）` にします。10件中3件が、500件中120件より上に来ないようにするためです。関門が止めた件数も出します。出さないと担当者の頑張り不足に見えます。

デモの51件では全走査でも数ミリ秒です。40万件の合成テナントで測り、日付式の全走査・キュー索引の NULLS 不一致・子テーブルの FK 未索引を直しています。テナント削除で子を走査し続けると、行ロックで全社の架電が止まります。見るのは実行計画に `Seq Scan on call_sessions` が無いことです。

# 起動

```bash
cd C:\devlop\KadenSaas
docker compose up -d
docker exec -i kadensaas-db-1 psql -U postgres -d kaden < scripts/seed-dev.sql
sh scripts/smoke-test.sh
```

http://localhost:3000 。デモの `operator@demo.example` / `password` です。株式会社チャーリー（03-1234-0003）は DNC です。発信が止まるのが正常です。

Twilio 未設定でも起動します。鳴らすときだけ SID / Token / Caller ID を3つ揃えます。1つだけ入れると起動しません。トンネル URL は Console と一致させてください。

```bash
sh scripts/check-boundaries.sh
cd api && ./gradlew test
cd voice && python -m pytest
```

# 作って分かったこと

1. **Twilio を呼ぶ場所を1つにし、queued 行を必須にする。** 関門を「気をつける」で守れない。
2. **RLS 未設定は 0 行。** DNC 照合をトランザクション外でやると、断った相手にかける。MANDATORY で落とす。
3. **登録しただけでは発信できない。** 発信者番号は自己申告で作らない。
4. **CallSid で upsert し、状態は単調前進。** completed より先に ringing が来ても巻き戻さない。
5. **音声ワーカーと業務 API を分ける。** 同期 SDK はイベントループに置かない。
6. **率は画面で組み立てる。** 関門で止めた件数を隠さない。superuser 接続では起動を止める。

公開前にデモパスワードと MinIO の既定を変えてください。この記事は法的判断の代替ではありません。

公開前は Zenn 管理画面で下書きのまま確認し、問題なければ `published: true` にするか、Web エディタへ本文を貼ってください。
