# 架電特化型SaaS

アウトバウンドコール業務（テレアポ・督促・契約更新フォロー）の基盤。
顧客リスト → 発信 → 通話 → 録音 → 文字起こし → AI要約 → 架電結果 → 再架電 → KPI
を一元化する。

```
Next.js ─┬─→ api    (Spring Boot)  業務 API・スキーマの所有者
         └─→ voice  (FastAPI)      Twilio・音声・AI
                    ↓
              PostgreSQL (RLS) / Redis / S3
```

---

## 守っている 5 つの原則

この 4 つを崩すと、静かに壊れる。壊れ方がどれも「エラーが出ない」ので、
最初から仕組みで防いでおく。

### 1. 発信は必ず 1 つの関門を通る

「かけてよいか」を判断する場所は [`DialingGate`](api/src/main/java/com/kadensaas/service/DialingGate.java) だけ。
DNC・架電時間帯・曜日・祝日・回数上限・二重発信をここで見る。

**迂回できない構造にしてある。** voice サービスは
`dial_state = 'queued'` の `call_session` が既に存在する場合しか発信しない
（[`dialer.py`](voice/app/telephony/dialer.py) の条件付き UPDATE）。
その行を作れるのは関門を通った経路だけなので、**行が無ければ鳴らせない**。

止めた発信も記録する（`dial_state = 'blocked'` ＋ 理由）。握りつぶすと
「なぜかけなかったのか」を後から説明できない。

```bash
# 守れているかは 1 コマンドで確認できる
sh scripts/check-boundaries.sh
```

### 2. 通話の同一性は CallSid で担保する

Twilio の `CallSid` に unique 制約を張り、webhook は upsert する。
自前の id で照合すると、発信 API の応答より先に webhook が届いたときに紐付かない。

### 3. 音声ストリームを API から分離する

Media Streams は 1 通話あたり毎秒 50 メッセージ。webhook と同じプロセスに載せると、
同時通話が増えるほど webhook の応答が遅れ、Twilio が再送を始める。
**負荷が高いときに、いちばん壊れてほしくない経路が最初に壊れる。**

`voice-web` と `voice-media` は必ず別サービスとして動かす。
スケールの軸も違う（前者は同時ユーザー数、後者は同時通話数）。

### 4. テナント分離は PostgreSQL の RLS

アプリの `where` 句に頼らない。1 箇所書き忘れただけで他テナントのデータが漏れ、
しかもテストは通ってしまう。

`SET LOCAL app.tenant_id` をトランザクション開始時に注入する
（[`TenantAwareTransactionManager`](api/src/main/java/com/kadensaas/tenant/TenantAwareTransactionManager.java) /
[`tenant_tx()`](voice/app/db/engine.py)）。未設定なら**全部見える**のではなく
**1 行も見えない**（fail closed）。

> **★ ここが最も踏みやすい。**
> Spring のトランザクションの外で DB に触ると `app.tenant_id` が設定されず、
> RLS が黙って 0 行を返す。例外も警告も出ない。画面には「データがありません」とだけ出る。
> 詳細は下の「トランザクションと RLS」を必ず読むこと。

### 5. 録音と文字起こしは個人情報として設計する

音声の実体は S3、メタデータは DB。バケットは非公開で、期限付き署名 URL でしか読ませない。
再生・ダウンロードは `recording_access_logs` に記録する。
保存期限はテナントごとに持ち、定期ジョブが消す。

「消す仕組み」を最初に作る。後から足すと、それまでに溜まった分が残り続ける。

---

## 起動

### 1. 依存

Docker / Docker Compose のみ。

```bash
docker compose up -d
```

6 サービスが立つ（db / redis / minio / api / voice / web）。
`api` が Flyway でスキーマを作るので、初回は 1 分ほどかかる。

### 2. デモデータ

```bash
docker exec -i kadensaas-db-1 psql -U postgres -d kaden < scripts/seed-dev.sql
```

| | |
| --- | --- |
| テナント | `demo`（デモ商事） / `other`（別会社） |
| 担当者 | `operator@demo.example` / `password` |
| 管理者 | `manager@demo.example` / `password` |
| キャンペーン ID | `cccccccc-0000-0000-0000-000000000001` |

**株式会社チャーリー（03-1234-0003）は DNC に入れてある。**
発信しようとすると関門が止める。それが正常な動作。

### 3. 確認

```bash
sh scripts/smoke-test.sh
```

http://localhost:3000 でログインし、`/operator` でキャンペーン ID を入れて
「次の 1 件を受け取る」。

### 4. Twilio を繋ぐ（任意）

Twilio が未設定でも起動する（電話機能だけ無効）。実際に鳴らすには:

```bash
# トンネルを立てる
cloudflared tunnel --url http://localhost:8001

# ★ PUBLIC_BASE_URL は Twilio Console に登録する URL と 1 文字も違ってはいけない
PUBLIC_BASE_URL=https://xxxx.trycloudflare.com \
TWILIO_ACCOUNT_SID=AC... TWILIO_AUTH_TOKEN=... TWILIO_CALLER_ID=+81... \
  docker compose up -d voice
```

---

## トランザクションと RLS（★ 必読）

この設計でいちばん危険な失敗は **「エラーが出ないまま 0 行が返る」**。
実際に開発中 2 回踏んだ。

| 起きたこと | 原因 |
| --- | --- |
| 顧客一覧が常に空。DB には 4 件ある | Spring Data の**派生クエリメソッド**（`findByXxx`）は、明示的な `@Transactional` が無いと Spring のトランザクションに入らない。`SimpleJpaRepository` のクラスレベル注釈は、そこに実装のあるメソッド（`findAll` など）にしか効かない |
| KPI の内訳が常に空 | `JdbcTemplate` を `@Transactional` の外で使っていた |
| ログインが必ず失敗する | `@Transactional` がメソッド開始時にトランザクションを開くため、その時点で `TenantContext` が空。あとから `set()` しても遅い |

### 守るべきこと

**1. リポジトリは必ず [`TenantScopedRepository`](api/src/main/java/com/kadensaas/repository/TenantScopedRepository.java) を継承する。**
`JpaRepository` を直接継承しない。基底に `@Transactional(readOnly = true)` があり、
派生クエリを含む全メソッドが Spring のトランザクションに入る。

**2. `JdbcTemplate` を使うクラスには `@Transactional` を付ける。**

**3. トランザクション必須の処理は `Propagation.MANDATORY` にする。**
`DialingGate` と `AuditService` がそう。関門が DNC 照合で 0 行を得ると
「拒否されていない」と判定して**断った相手に電話がかかる**。
静かに間違えるより、落ちるほうがよい。

**4. 新しい読み取り API を足したら `scripts/smoke-test.sh` に 1 行足す。**
「データがあるはずのところにデータがあるか」を確かめるのが唯一の防御になる。
200 が返るだけでは見つからない。

### voice 側

業務データに触る経路は `tenant_tx()` だけ。素の接続を borrow する関数を公開していない。
テナントが確定していない処理（webhook の入口）は `system_tx()` を使い、
RLS 対象のテーブルには触れない。

`SET LOCAL` はトランザクション内でしか効かない。接続はプールで使い回されるので、
トランザクションを越えて値が残る形（`SET` や接続初期化フック）にしてはいけない。
残ると、その接続を拾った別の処理が前のテナントとして動く。

---

## サービスの境界

| | api (Spring Boot) | voice (FastAPI) |
| --- | --- | --- |
| スキーマ | **所有者**（Flyway） | 持たない |
| Twilio | **触らない**（SDK も入れない） | **ここだけ** |
| 発信の判断 | **関門を持つ** | 持たない（queued の行があるかだけ見る） |
| JWT | **発行する** | 検証のみ |
| 担当 | 顧客・リスト・結果・KPI・課金・CRM | webhook・音声・文字起こし・AI |

`JWT_SECRET` は両サービスで同一の値でなければならない。
ポリグロット構成でいちばん踏みやすいのがここで、片方で生成し直すと
「api では通るのに voice で 401」という切り分けにくい壊れ方をする。

---

## 通話の状態

1 本の `status` に詰め込まない。性質の違う 3 つを別々に持つ。

| | 誰が書くか | どこ |
| --- | --- | --- |
| `dial_state` | 機械（Twilio）だけ | `call_sessions.dial_state` |
| `disposition_code` | 人 / AI | `call_dispositions` の履歴が正。`call_sessions` は最新値のキャッシュ |
| 後処理の進捗 | ジョブ | `recordings` / `transcripts` / `ai_analyses` がそれぞれ持つ |

1 列にすると「通話は終わったが文字起こしは処理中」が表現できず、どちらかを潰す。

### 状態は巻き戻らない

Twilio の webhook は順不同で届き、再送もある。素直に代入すると、遅れて届いた
`ringing` が `completed` を上書きする。

`dial_state_rank` を**生成列**で持ち、更新は必ず
`where dial_state_rank < call_dial_state_rank(:new)` を付ける。
弾いた分も `call_events` に `applied = false` で残す。残さないと
「なぜ反映されなかったか」を追えない。

---

## 検証

```bash
sh scripts/check-boundaries.sh   # 設計上の境界（Twilio の位置・関門の唯一性）
sh scripts/smoke-test.sh         # 起動中のスタックに対する機能確認
```

```bash
# スキーマが「書いてあるだけでなく効いている」ことの確認
docker exec -i kadensaas-db-1 psql -U postgres -d kaden < scripts/verify-schema.sql
```

9 項目を確認する。テナント分離／他テナントを騙った書き込みの拒否／未設定時の
fail closed／二重発信の拒否／通話終了後の再架電／状態の単調前進／理由なし
blocked の拒否／監査ログの削除拒否／KPI ビューの越境防止。

```bash
cd voice && .venv/Scripts/python -m pytest    # Twilio 署名検証（陽性対照つき）
cd api && ./gradlew test
```

> **署名検証のテストに陽性対照を必ず含める。**
> 「署名なしで 403」だけでは、検証が壊れて常に 403 を返す実装でも通ってしまう。

---

## KPI

**率を返さない。分子と分母を返す。**
率だけを返すと画面ごとに解釈が変わり、「同じ指標なのに数字が違う」で毎月揉める。
`32.4%` ではなく `162 / 500` を渡し、表示側で組み立てる。

定義は [`kpi_call_facts`](api/src/main/resources/db/migration/V6__roles_and_kpi_views.sql) ビューが唯一の出所。
ここに新しい集計 SQL を書き足さない。書き足すと、api と voice で「接続率」が別物になる。

分母の扱いは `disposition_codes.excluded_from_denominator` が持つ。
無効番号を分母から外すなら、**無効番号率を併記する**運用が要る。
外したままだとリスト品質が悪いほど接続率が良く見える。

**「関門が止めた件数」を必ず画面に出す。** ここが想定より多いとき、
架電数が伸びない原因はリスト側（DNC 過多・時間帯外）にある。
出していないと、担当者の頑張り不足として扱われてしまう。

---

## デプロイ（AWS）

[`infra/terraform`](infra/terraform)。5 サービスに分ける。

| サービス | 公開 | 台数の軸 |
| --- | --- | --- |
| `api` | ALB `/api/v1/*` | 同時ユーザー数 |
| `voice-web` | ALB `/twilio/*` `/internal/*` | webhook の流量 |
| `voice-media` | ALB `/media` | **同時通話数** |
| `voice-jobs` | **公開しない** | 常に 1 台 |
| `web` | ALB `/*` | 同時ユーザー数 |

- `voice-jobs` に ALB を繋がない。HTTP を持たないので、ターゲットグループを付けると永久にヘルスチェック待ちになる。
- `voice-jobs` は 1 台だけ。複数だと同じ録音を 2 回取りに行く。
- ALB の `idle_timeout` を 3600 にしてある。既定の 60 秒だと、無音が続いた通話で Media Stream が切断される。
- 秘密情報は Secrets Manager。**Terraform で値を設定していない**（tfstate に平文で残さないため）。作成後に CLI で投入する。

### Railway

ルートに `Dockerfile` を置いていない（5 サービスあるため）。Railway は既定で
リポジトリのルートに `Dockerfile` を探すので、**サービスごとにどの Dockerfile を
使うかを指定しないと** 次のエラーで失敗する。

```
couldn't locate the dockerfile at path Dockerfile in code archive
```

サービスは 5 つ作る。それぞれで **Settings → Build → Dockerfile Path** を設定する。

| サービス | Dockerfile Path | Start Command | ドメイン |
| --- | --- | --- | --- |
| `api` | `api/Dockerfile` | （空。イメージの既定） | 生成する |
| `voice-web` | `voice/Dockerfile` | `entrypoint web` | 生成する |
| `voice-media` | `voice/Dockerfile` | `entrypoint media` | 生成する |
| `voice-jobs` | `voice/Dockerfile` | `entrypoint jobs` | **生成しない** |
| `web` | `web/Dockerfile` | （空） | 生成する |

`railway/*.toml` に同じ内容を用意してある。Settings → **Config as Code** に
パス（例 `railway/api.toml`）を入れれば設定ファイルから読ませられる。

> **★ ただし config as code が適用されないことがある。**
> 別プロジェクトで `railway.toml` を置いても `preDeployCommand` が実行されず、
> 原因を特定できなかった。**確実なのは Settings に直接書く方法**なので、
> まずそちらで動かし、config as code はうまくいけば使う、という順序で扱うこと。
> 適用されたかどうかは「`voice-jobs` が Active になるか」（ヘルスチェックが
> 付いていないこと）などで確認できる。

#### 各サービスの変数

`JWT_SECRET` は **api / voice-web / voice-media / voice-jobs で同一の値**にする。
ずれると「api では通るのに voice で 401」になり、切り分けに時間がかかる。

`voice-media` には `PORT` と `MEDIA_PORT` を**同じ値**（例 `8080`）で入れる。
`entrypoint media` は `MEDIA_PORT` を見て bind するので、`PORT` だけ入れると
Railway が待つポートと合わずヘルスチェックが通らない。

`web` の `NEXT_PUBLIC_*` は**ビルド時に埋め込まれる**。Variables に入れても
反映されないので、Settings → Build → Build Arguments で渡すこと。

#### DB の接続情報

`DATABASE_URL` を `postgresql://user:pass@host:5432/db` の形で渡せばよい
（Railway が Postgres を繋いだときに配る形そのまま）。
api 側で `jdbc:postgresql://...` + ユーザー / パスワードに分解する。
`SPRING_DATASOURCE_*` を手で 3 つに分けて設定する必要はない。

`DATABASE_MIGRATOR_URL` も同じ形で渡す。未設定なら Flyway は
`spring.datasource` の資格情報をそのまま使う（別のロールに勝手に
フォールバックしない）。本番では BYPASSRLS を持つロールを渡して分離すること。

> **★ 変数名を打ち間違えても何も言われない。**
> `DATABSE_URL` のような綴り間違いがあると、アプリは
> application.yml の既定値（localhost）にフォールバックして
> 「Connection to localhost:5433 refused」で落ちる。
> ログに出るのは接続エラーだけで、「変数名が違う」とは出ない。
> 502 になったらまず `railway variables` で名前を確認すること。

#### マイグレーション

`api` の起動時に Flyway が流れる。`voice-*` は `api` が一度起動してから上げる。

Railway の Postgres が配る `DATABASE_URL` は superuser なので、
**そのまま使うと RLS が効かない**。下の「マネージド Postgres での注意」を必ず読むこと。

### ★ マネージド Postgres での注意

**`db/bootstrap-roles.sql` を必ず 1 回流す。**

マネージド DB が配る既定の接続ユーザーは superuser か所有者であることが多い。
そのまま `DATABASE_URL` に使うと、`force row level security` は superuser に効かないため、
**RLS が「書かれているのに 1 行も効かない」状態**になる。アプリは正常に動くので気付けない。

起動時に接続ロールを検査して止める。api は
[`RlsEnforcementCheck`](api/src/main/java/com/kadensaas/config/RlsEnforcementCheck.java)、
voice は [`assert_rls_enforced`](voice/app/db/engine.py)。
**両方に置くこと。片方だけ守っても、もう片方から漏れる。**

`APP_ENV=production` のときだけ起動を止める（開発中は警告のみ）。
Flyway は BYPASSRLS を持つ `kaden_migrator` で流すのが正しいので、検査の対象外。
見るのはアプリが実際に使う接続。

```
アプリの接続ロール postgres が superuser のため、RLS が適用されません。
テナント分離が無効の状態です
```

これが出たら **意図した停止**。動かないほうが、静かに漏れているよりよい。

また PostgreSQL 15 以降、`public` スキーマの CREATE 権限が PUBLIC から外れた。
データベース単位の grant だけでは Flyway が最初のテーブルすら作れない。

---

## 症状から引く

| 症状 | 見るところ |
| --- | --- |
| Webhook が全件 403 | `PUBLIC_BASE_URL` と Twilio Console の URL。末尾のスラッシュだけでもずれる。[`signature.py`](voice/app/telephony/signature.py) |
| 一覧が空。DB にはデータがある | **トランザクションの外で DB に触っている。** 上の「トランザクションと RLS」 |
| ログインが必ず失敗する | 同上。テナントを設定する前にトランザクションが始まっている |
| 同じ通話が 2 行に増える | `provider_call_sid` の unique と upsert |
| 同じ相手に 2 回かかる | `call_sessions_inflight_uniq`（部分ユニーク）。関門の事前チェックは競合に弱く、砦は DB 側 |
| 通話の状態が巻き戻る | 更新に `dial_state_rank <` を付けているか |
| API が通話中だけ遅い | `voice-media` を別サービスで動かしているか（原則 3） |
| 断った相手に再架電した | 関門を通らない発信経路。`sh scripts/check-boundaries.sh` |
| コールリストが少しずつ枯れる | 予約の期限切れ解放ジョブが動いているか |
| 「N 件の問題があります」で起動しない | 必須の環境変数が未設定。**意図した挙動** |
| Flyway が `permission denied for schema public` | PostgreSQL 15+。スキーマへの CREATE 権限が要る |
| `create extension` で `permission denied` | superuser が要る拡張を使っている。この構成では pgcrypto は不要（`gen_random_uuid()` はコアにある） |

---

## 法令について

このリポジトリは法的判断を代替しない。実サービス化の前に、対象地域・業種について
専門家または担当部門の確認を行うこと。特に次を確認する。

個人情報・プライバシー / 通話録音の告知と同意 / 営業電話・勧誘の規制 /
オプトアウトと Do Not Call / 発信者番号 / 電気通信関連制度 /
AI による自動応答・自動発信 / データの保存地域 / 外部の AI・音声 API への
データ送信。

仕組みで担保しているのは「架電時間帯」「曜日」「祝日」「再勧誘拒否」「回数上限」
「録音の保存期限」までで、これらの設定値が法令に対して妥当かどうかは判断していない。

---

## 未実装

- CRM（Salesforce / HubSpot）連携の Adapter 実体。テーブルと outbox は用意済み
- Stripe の決済処理。プラン・サブスクリプション・従量集計のテーブルは用意済み
- CSV 一括取込
- 録音の再生画面
- 自動発信（プログレッシブ / プレディクティブ）
