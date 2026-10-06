# 架電特化型SaaS

> **Domain SaaS / AI Calling Platform** — 発信制御、音声、録音、文字起こし、AI要約、再架電、KPIを統合するマルチテナント架電SaaSです。
>
> **Stack:** Java · Spring Boot · Python · FastAPI · Next.js · PostgreSQL · Redis · Twilio

アウトバウンドコール業務（テレアポ・督促・契約更新フォロー）の基盤。
顧客リスト → 発信 → 通話 → 録音 → 文字起こし → AI要約 → 架電結果 → 再架電 → KPI
を一元化する。

```
Next.js ─┬─→ api    (Spring Boot)  業務 API・スキーマの所有者
         └─→ voice  (FastAPI)      Twilio・音声・AI
                    ↓
              PostgreSQL (RLS) / Redis / S3
```

## Portfolio Overview

| Item | Description |
|---|---|
| **Problem** | アウトバウンドコールでは、発信可否・通話状態・録音・AI処理・テナント分離・監査を別々に実装すると、静かな誤動作や情報漏えいが起きやすい |
| **Solution** | 顧客リストから発信、通話、録音、文字起こし、AI要約、再架電、KPIまでを一つのマルチテナントSaaSとして統合 |
| **Architecture** | Next.js → Spring Boot business API / FastAPI voice services → PostgreSQL RLS / Redis / S3-compatible storage → Twilio |
| **Safety by Design** | DialingGate、CallSid idempotency、fail-closed RLS、音声ストリーム分離、録音アクセス監査・保存期限 |
| **Differentiators** | 「動くデモ」だけでなく、境界検査・スモークテスト・40万件性能検証・権限表整合性までコードで検証 |

## Architecture

```text
                         ┌──────────────────────┐
                         │       Next.js        │
                         │ Operator / Admin UI  │
                         └──────────┬───────────┘
                                    │
                   ┌────────────────┴────────────────┐
                   ▼                                 ▼
        ┌─────────────────────┐          ┌─────────────────────┐
        │ Spring Boot API     │          │ FastAPI Voice       │
        │ Business / Schema   │          │ Twilio / Audio / AI │
        │ DialingGate / Auth  │          │ Webhook / Media     │
        └──────────┬──────────┘          └──────────┬──────────┘
                   │                                 │
                   └──────────────┬──────────────────┘
                                  ▼
                    PostgreSQL RLS / Redis / S3
                                  │
                                  ▼
                               Twilio
```

## Engineering Differentiators

- **Dialing safety as an invariant** — every outbound call must pass a single `DialingGate`; blocked attempts are retained with reasons.
- **Webhook idempotency** — Twilio `CallSid` is the external identity and duplicate/reordered callbacks are handled explicitly.
- **Fail-closed tenant isolation** — PostgreSQL RLS returns no business rows when tenant context is absent instead of exposing all tenants.
- **Independent scaling boundaries** — webhook/API traffic and high-frequency media streams are separated because their load characteristics differ.
- **Privacy-aware recordings** — object storage is private, access uses short-lived signed URLs, access is audited, and retention is tenant-controlled.
- **Authorization verification** — declared permission matrices are exercised against real endpoints so documentation cannot silently drift from enforcement.
- **Production-scale validation** — performance scripts exercise approximately 400,000 synthetic call records rather than relying only on demo-size data.

## Reliability & Security Model

```text
Request
  │
  ├─ Tenant Context ──► PostgreSQL RLS (fail closed)
  │
  ├─ Authorization ───► Server-side role enforcement
  │
  └─ Dial Request ────► DialingGate
                         ├─ DNC
                         ├─ allowed hours / days
                         ├─ attempt limits
                         ├─ duplicate-call prevention
                         └─ tenant / platform stop switches
                                  │
                                  ▼
                             queued session
                                  │
                                  ▼
                                Twilio
```

## Portfolio Role

KadenSaas is the **Enterprise Domain SaaS / AI Calling Architecture** asset in this portfolio. It demonstrates how Java/Spring business services, Python/FastAPI real-time services, PostgreSQL tenant isolation, telephony, AI processing, privacy controls, and operational verification can be combined without collapsing all responsibilities into one service.

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

## 利用手順パレット

認証後の画面に、ドラッグで動かせる手順パネルが出る
（[`GuidePalette`](web/components/GuidePalette.tsx)）。構成・セットアップ・
つまずいたときの見どころを、画面を開いたまま参照できる。

- 位置と開閉の状態を `localStorage` に覚える。毎回どかす作業を繰り返させない
- ドラッグ位置はビューポート内に丸める。一度でも画面外に出ると掴み直せない
- **ログイン画面と登録画面には出さない。** 構成やサービス名は運用の手がかりで、
  未認証の相手に見せる理由が無い

内容を変えるときは `GuidePalette.tsx` の JSX を直接編集する。
別ファイルのデータにしていないのは、リンクや強調を含む短い文章で、
構造化しても読みやすくならないため。

---

## 電話機能を有効にする

`/settings/telephony`。発信者番号・録音・留守番電話の検出を設定し、
**いま発信できるかどうかを診断**する。

### 診断がこの画面の主目的

架電が止まる原因は毎回同じ数種類だが、それぞれ別の場所に出る。
1 箇所に集めないと、毎回ログを掘ることになる。

```
✓ 音声サービスの接続先   http://voice:8001
✓ システム全体の発信     有効
✓ 発信者番号             +81300000000
! このテナントの発信      停止中（管理画面で再開できます）
! 架電可能な時間帯        本日は架電対象外の曜日です。設定: 09:00-20:00 Asia/Tokyo
✓ 架電待ちの相手          10 件
```

「発信できません」ではなく、項目ごとに何が足りないかを返す。
診断は **manager でも見られる**（止まっている理由を知りたいのは設定者だけではない）。
設定の変更は admin 限定。

### 停止スイッチは 2 段

| | どこで切る | 用途 |
| --- | --- | --- |
| 全体 | 環境変数 `KADEN_DIALING_ENABLED` | 基盤側の事故。全テナントを止める |
| テナント別 | 管理画面 `tenant_telephony.dialing_enabled` | 苦情対応。**1 社だけ**止める |

全体だけだと「1 社から苦情が来たのでその会社への発信だけ止める」ができず、
全テナントを巻き添えにするか何もしないかの二択になる。テナント別だけだと、
基盤の事故で全部止めたいときにテナントの数だけ操作することになる。両方要る。

テナント別は画面から即座に切り替わる。**デプロイを待たない**のが要点。

### 発信者番号が未設定なら発信を止める

`tenant_telephony` に行が無いテナントは、関門が `telephony_not_configured` で
止める。ここを素通りさせると `from` が null のまま Twilio に渡り、
分かりにくいエラーになる。

**保存できた＝使える、ではない。** 番号が Twilio で購入・検証済みかどうかは
保存時に確かめない（確かめるには Twilio の資格情報が要り、それを持つのは
voice サービス）。未検証の番号は保存できるが、発信は Twilio 側で失敗する。

### Twilio の資格情報

`voice-web` / `voice-media` / `voice-jobs` の 3 サービスに
`TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` / `TWILIO_CALLER_ID` を設定する。
**3 つ揃わないと起動しない**（1 つだけ設定された中途半端な状態を作らないため）。
全部空なら電話機能を無効にして起動する。

設定後、`PUBLIC_BASE_URL` を Twilio Console の Webhook URL に
**1 文字違わず**登録すること。ずれると署名が一致せず Webhook が全件 403 になる。

---

## テナントの登録

`POST /api/v1/signup` と `/signup` 画面。組織と、最初の管理者アカウントを作る。

### 有効化

**`KADEN_SIGNUP_TOKEN` を設定するまで、この機能は存在しない**（API は 404 を返す）。
設定すると有効になり、`X-Signup-Token` ヘッダの一致を要求する。

```bash
openssl rand -hex 24     # 生成してサービスの変数に入れる
```

変数 1 つで「有効化」と「保護」を兼ねている。分けると
「有効にしたがトークンを付け忘れて全開だった」という状態が作れてしまう。
無効時に 403 ではなく 404 を返すのは、URL の存在を知らせないため。

### なぜここが難しいか

**テナントがまだ無い状態で `users` に書く**、この系で唯一の場所になる。

テナントは `SET LOCAL app.tenant_id` でトランザクション開始時に注入される。
1 つの `@Transactional` メソッドで `tenants` と `users` を両方書くと、
users への insert がテナント未設定のトランザクションで走り、RLS の
`with check` に弾かれる。

そこで 2 トランザクションに分ける。

1. `tenants` に insert（RLS が無いので context 不要）
2. `TenantContext.set(新しい id)`
3. `users` に insert（新しいトランザクション。ここで初めて RLS が効く）

`REQUIRES_NEW` が効くよう **別 Bean**（`TenantProvisioning`）にしてある。
同じクラス内の呼び出しは Spring のプロキシを通らない。

2 に失敗すると「誰もログインできないテナント」が残り、slug も占有される。
その場合は `tenants` を消して巻き戻す。

### 実装時に踏んだ罠

**JPA は null のフィールドも INSERT 文に含める。** `calling_hours_start` などを
セットせずに保存すると、DB の default ではなく NULL が入ろうとして
not-null 制約に弾かれる。必須列はすべて明示的に埋めること。

**`DataIntegrityViolationException` を一律「識別子の重複」と報告しない。**
最初そう書いていたため、上の not-null 違反が `slug_taken` として表示され、
存在しないはずの slug が「すでに使われています」と出た。**嘘の理由は
原因の特定を遅らせる。** 制約名で判定し、それ以外は隠さずログに残す。

### 登録しても電話はかけられない

発信には `tenant_telephony`（発信者番号）の設定が要る。番号は購入・検証が
必要で自己申告させてよいものではないので、登録では作らない。

---

### 公開サインアップ（誰でも登録できる）

`KADEN_SIGNUP_MODE` で切り替える。既定は `open`。

| 値 | 動作 |
| --- | --- |
| `open`（既定） | 誰でも登録できる |
| `token` | `X-Signup-Token` が `KADEN_SIGNUP_TOKEN` と一致する場合だけ |
| `disabled` | 404。機能ごと存在しないものとして扱う |

> `token` を指定して `KADEN_SIGNUP_TOKEN` が空なら**起動時に落とす**。
> 「有効にしたがトークンを付け忘れて全開だった」を作らせないため。

#### ★ 誰でも登録できることを、どうやって安全にしているか

**登録しただけでは 1 本も発信できない。** これが主軸で、他は補助でしかない。

発信には `tenant_telephony`（発信者番号）が要るが、登録処理はそれを作らない。
発信者番号は購入・検証が必要で、自己申告で登録させてよいものではない。
結果として、登録直後のテナントは関門に `telephony_not_configured` で止められる。

ここが崩れると、この製品は「誰でも迷惑電話をかけられる基盤」になる。
`OpenSignupTest` が**実際に発信を試して**固定してある
（登録処理が発信者番号を作るように変えると、そのテストが落ちる）。

補助として IP ごとの回数制限を入れてある（既定: 60 分に 5 回）。
数えるのは DB で、アプリのメモリには持たない。持つとインスタンスが
2 つに増えた瞬間に上限が 2 倍になり、しかも誰も気付かない。
失敗した試行も数える。成功だけ数えると、どの識別子が空いているかを
調べる総当たりが素通りする。

> **IP は詐称できる。** `X-Forwarded-For` を信じている以上、回数制限は
> 「事故と雑な自動化を止める仕組み」であって「本気の攻撃者を止める仕組み」ではない。
> 本気で守るなら CAPTCHA かメール確認が要る（**未実装**）。
> ここを誤解すると、守れているつもりで公開してしまう。

`signup_attempts` の IP は個人情報になりうるので、
`SignupRateLimiter#purgeOlderThanDays` で古い行を消せるようにしてある。

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

## 画面

```
架電SaaS
├─ ダッシュボード … 発信数 / 接続率 / 会話率 / 成果率 / 平均通話時間
├─ 顧客リスト   … 顧客名 / 電話番号 / 担当者 / [架電]
├─ 架電履歴     … 発信日時 / 通話時間 / 結果 / 録音
├─ 分析         … 時間帯 / 曜日 / 担当者 / 閉門理由
└─ 管理         … ユーザー / Twilio / 権限
```

共通のナビゲーションは `web/components/AppNav.tsx`。役割に応じて項目を出し分けるが、
**これは「押しても 403 になるものを見せない」ための配慮であって、権限の実装ではない。**
判定はサーバーにしかない。

### ダッシュボードと分析で率の定義がずれないようにする

集計の定義（何を「接続」と数えるか）は `kpi_call_facts` ビューだけが持つ。
ダッシュボードも分析も、このビューの `counts_in_denominator` / `is_connected` /
`is_success` をそのまま使い、独自の条件を書かない。書き足すと
「同じ指標なのに画面によって数字が違う」が起きる。

率は API から返さない。分子と分母を返し、画面が `32.4%（162 / 500）` の形に組み立てる。
率だけを並べると、10 件で 3 件成功した人が 500 件で 120 件成功した人より上に来る。
担当者別は人の評価に使われるので、母数が見えない形では出さない。

### 架電履歴

オペレーターには自分の通話だけを返す。**この絞り込みはサーバー側で行う**
（`CallHistoryController`）。画面で出し分けるだけだと、API を直接叩けば他人の履歴が読める。
`operatorId` を指定しても上書きできないことを `CallHistoryTest` が確かめている。

止めた発信（`blocked`）も履歴に出す。「かけたが繋がらなかった」と「そもそもかけていない」は
別物で、後者が見えないと「なぜ架電数が伸びないのか」が分からない。

### 録音の再生

再生 URL を出すのは **voice だけ**（`voice/app/api/recordings.py`）。
S3 の資格情報を持つのが voice だけだからで、api に署名を作らせると
保管先の鍵を 2 つのサービスに配ることになる。api は録音の「有無」だけを返す。

- URL は 5 分で切れる。長い URL はチャットや議事録に貼られて権限の外に出ていく
- 参照は必ず `recording_access_logs` に記録する。記録の書き込みと同じ
  トランザクションで発行するので、記録できなければ URL も返らない
- オペレーターは自分がかけた通話の録音だけ。「無い」と「見せない」は
  同じ 404 で返す（区別すると id の総当たりで存在が分かる）

### 管理：ユーザーと権限

初期パスワードはシステムが生成し、**一度だけ**応答に含める。保存も再表示もしない。
管理者に考えさせると弱い値が使われ、しかも複数人で使い回される。

- 削除はできない。無効化だけ。通話履歴と監査ログが担当者を参照しているので、
  行を消すと「誰がかけたか分からない通話」が残る
- 最後の管理者は降格も無効化もできない。できると、設定を変えられる人が
  誰もいないテナントが出来上がり、復旧に DB を直接触ることになる
- 監査ログには役割しか残さない。初期パスワードもメールアドレスも入れない

### ★ 権限表が嘘にならないようにする

管理画面の「権限」は `PermissionCatalog` を表示しているだけで、実際に権限を
決めているのは `SecurityConfig` と各 `@PreAuthorize`。放っておけば必ずずれる。
そして、ずれた権限表は**「画面には管理者のみと書いてあるのに実は operator でも通る」**
という、誰も嘘だと気付かないまま運用される種類の不具合になる。

そこで各項目に実際の入口（メソッドとパス）を持たせ、`PermissionMatrixTest` が
3 つの役割すべてで叩いて宣言と一致するかを確かめている。ずれるとテストが落ちる。

> **探針が認可に届いていないと、検査したつもりで何も見ていないことになる。**
> Spring MVC は引数の解決を先に行い、`@PreAuthorize` はその後に走る。
> 必須パラメータや本文が足りないと、認可に到達する前に 400 で返る。
> それを「通った」と数えると全項目が素通りするので、
> 全役割が 400 になる項目は「検査できていない」として落としている。

---

## 検証

```bash
sh scripts/check-boundaries.sh   # 設計上の境界（Twilio の位置・関門の唯一性）
sh scripts/smoke-test.sh         # 起動中のスタックに対する機能確認
sh scripts/perf-bench.sh         # 実用規模（通話 40 万件）での性能
```

```bash
# スキーマが「書いてあるだけでなく効いている」ことの確認
docker exec -i kadensaas-db-1 psql -U postgres -d kaden < scripts/verify-schema.sql
```

10 項目を確認する。テナント分離／他テナントを騙った書き込みの拒否／未設定時の
fail closed／二重発信の拒否／通話終了後の再架電／状態の単調前進／理由なし
blocked の拒否／監査ログの削除拒否／KPI ビューの越境防止／削除で全走査になる
外部キーが無いこと。

### 性能

デモデータは通話 51 件しかない。**その規模では、遅くなる書き方をしても
気付けない。** 全走査でも索引走査でも数ミリ秒で返るからで、
実際に下の 3 つはどれも 51 件のときは 3ms 以下だった。

```bash
sh scripts/perf-bench.sh          # 投入 → 実行計画と HTTP を計測 → 削除
sh scripts/perf-bench.sh --keep   # 削除しない（EXPLAIN を追いたいとき）
```

通話 40 万件・1 テナント・13 か月ぶんの合成データを専用テナント（`perfco`）に
入れて測る。`demo` / `other` には触れない。

実測して直したのは 3 つ。どれも「51 件では見えない」種類だった。

**1. 集計の絞り込みが式になっていた。**
`local_date` は `(started_at at time zone t.timezone)::date` で、索引が無い。
しかも `timezone` は結合先の `tenants` にあるので、プランナは
「全件読んでから式を評価する」しか選べない。KPI・分析・履歴の総件数が
すべて `call_sessions` の全走査になっていた。