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
```

```bash
# スキーマが「書いてあるだけでなく効いている」ことの確認
docker exec -i kadensaas-db-1 psql -U postgres -d kaden < scripts/verify-schema.sql
```

9 項目を確認する。テナント分離／他テナントを騙った書き込みの拒否／未設定時の
fail closed／二重発信の拒否／通話終了後の再架電／状態の単調前進／理由なし
blocked の拒否／監査ログの削除拒否／KPI ビューの越境防止。

### 自動テスト

```bash
python scripts/test-report.py --serve
```

api（JUnit）と voice（pytest）の両方を実行し、結果を 1 枚の HTML にまとめて
`http://127.0.0.1:877/` で表示する。個別に走らせたい場合は下記。

```bash
cd api && ./gradlew test                      # 42 件（Testcontainers。Docker が要る）
cd voice && python -m pytest                  # 5 件（Twilio 署名検証）
python scripts/test-report.py --no-run        # 直近の結果からレポートだけ作り直す
```

レポートは失敗を先頭に並べる。読まれるのは落ちているときなので、
「何件通ったか」より「何が壊れているか」を先に出す。失敗があれば
終了コード 1 を返すので、そのまま CI に置ける。127.0.0.1 に限定して配信する
（結果には内部のクラス名とスタックがそのまま載るため、既定で LAN に開かない）。

### ★ が付いたテストについて

テスト名の先頭の ★ は、開発中に実際に踏んだ不具合を固定しているという印。
いずれも**例外が出ず、200 が返り、ただ結果が 0 件になる**（あるいは
間違った時刻で判定される）種類の失敗で、疎通確認では見つからない。
レポート上では「過去の不具合」として印が付く。

主なものは次のとおり。

| テスト | 固定している失敗 |
| --- | --- |
| 派生クエリメソッドでもテナントが効く | `TenantScopedRepository` を継承し忘れると、派生クエリが Spring のトランザクションに入らず RLS が常に 0 件を返す |
| テナント未設定なら 1 行も見えない | fail open になると、認証を通らない経路から全テナントが読める |
| 登録直後のテナントの必須列が埋まっている | `hibernate.jdbc.time_zone: UTC` が `time` 型にも効き、架電可能時間が JVM のタイムゾーン分ずれて DB に入る |
| 止めた発信も理由つきで記録される | 握りつぶすと「なぜかけなかったのか」を後から説明できない |
| 発信者番号が未設定なら発信しない | 設定が無いまま通すと `from` が null のまま Twilio に渡る |
| 架電結果に DO_NOT_CALL を選ぶと拒否リストに入る | 分かれていると、次のキャンペーンで同じ人にかかる |

> **署名検証のテストに陽性対照を必ず含める。**
> 「署名なしで 403」だけでは、検証が壊れて常に 403 を返す実装でも通ってしまう。

> **テストは、落ちることを一度確かめてから信じる。**
> 上記はいずれも、実装をわざと壊して赤くなることを確認してある。
> 通るだけのテストは、何も見ていなくても通る。

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

### must be owner of table xxx（マイグレーションが失敗する）

Flyway を流すロールが、対象のテーブルの**所有者ではない**。

PostgreSQL では権限（grant）と所有権（owner）が別物で、
`all privileges` を持っていても所有者でなければ `alter` も `drop` もできない。
`create table` は通るので、**新しいテーブルを足すマイグレーションは成功し続け、
既存のテーブルを変更する最初のマイグレーションで初めて表面化する。**

実際にそうなった。最初のデプロイで Flyway が `postgres` として走り、
V1〜V9 のテーブルは全部 postgres 所有になった。その後 Flyway の接続を
`kaden_migrator` に切り替えたが、所有権は移らないまま残り、
既存テーブルを `alter` する V10 で止まった。

一度だけ superuser で流す:

```bash
railway ssh -s Postgres 'psql -U postgres -d railway' < db/transfer-ownership.sql
```

> **テストを superuser で走らせている限り、この種の失敗は必ず本番で初めて見つかる。**
> superuser は所有権の検査を素通りするため。`AbstractIntegrationTest` は
> Flyway を `kaden_migrator` で流し、`SchemaOwnershipTest` が
> 「テーブルの所有者が migrator であること」を固定している。
> ここを superuser に戻すと、その 2 件が落ちる。

> **GRANT は権限が足りなくてもエラーにならない。**
> 所有者でないロールが `grant usage on schema public` を実行すると、
> PostgreSQL は ERROR ではなく WARNING を出し、権限は付与されないまま通る。
> 「マイグレーションは成功したのに権限が無い」が起こりうるので、
> スキーマの所有権も `kaden_migrator` に渡しておく。

### password authentication failed for user "kaden_app" / "kaden_migrator"

DB 側のロードのパスワードと、各サービスの `DATABASE_URL` が食い違っている。
**資格情報は 2 か所（DB のロード と 各サービスの環境変数）にあり、
片方だけ変えるとこうなる。** 実際にこれで全サービスが起動不能になった。

紛らわしいのは、**すでに起動しているサービスはしばらく生き残る**こと。
asyncpg / HikariCP はプールを保持しているので `/healthz` は 200 を返し続ける。
壊れるのは「再起動したとき」と「プールを広げようとしたとき」なので、
一部だけ落ちているように見える。実際には全部が壊れている。

復旧は、DB と全サービスを**同時に**そろえる:

```bash
railway connect Postgres
```
```sql
alter role kaden_app      password '...';
alter role kaden_migrator password '...';
```

```bash
# api は両方、voice の 3 つは DATABASE_URL だけ
railway variables -s KadenSaas   --set "DATABASE_URL=postgresql://kaden_app:PW1@postgres.railway.internal:5432/railway"   --set "DATABASE_MIGRATOR_URL=postgresql://kaden_migrator:PW2@postgres.railway.internal:5432/railway"
```

> **パスワードは 16 進など URL に安全な文字にする**（`openssl rand -hex 24`）。
> `@` `/` `#` `?` が入ると DSN の区切りとして解釈され、
> 「パスワードが違う」ではなく「ホストが見つからない」など別の症状で出る。

再発を防ぐには、`DATABASE_URL` を 1 か所に集約して参照させる:

```bash
railway variables -s voice-jobs --set 'DATABASE_URL=${{KadenSaas.DATABASE_URL}}'
```

> **参照へ切り替えるのは、パスワードを直した後にすること。**
> 変数を変えると再デプロイが走る。壊れた値のまま切り替えると、
> 古いプールで生き残っていたサービスまで落ちる。

なお `DATABASE_MIGRATOR_URL` は api しか使わない。voice の 3 サービスにも
設定されているが不要で、しかも `kaden_migrator` は BYPASSRLS を持つ。
使わないサービスに RLS を素通りできる資格情報を置かない。

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
- 自動発信（プログレッシブ / プレディクティブ）
- 通話の文字起こし・AI 要約の画面表示（パイプラインと格納先は用意済み）
