---
name: kaden-saas
description: 架電特化型SaaS（アウトバウンドコール／テレアポ・督促・契約更新フォロー・顧客フォロー）の上流設計を行う。要件定義、参照アーキテクチャ、データモデルと通話状態遷移、API 仕様、画面一覧、フェーズ分割（MVP → AI → SaaS化 → 高度化）、CTI・電話API の抽象化、録音・文字起こし・LLM 通話分析の非同期パイプライン設計、CRM/SFA 連携の Anti-Corruption Layer、架電 KPI の定義、マルチテナント・RBAC・監査ログ、SaaS 課金、テスト計画までを含む。「架電SaaSの要件をまとめたい」「テレアポ／督促システムの構成を考えて」「CTIや電話APIをどう抽象化するか」「通話録音と文字起こし・AI要約の設計」「架電KPIをどう定義するか」「CRM連携の設計」「オペレーター画面に何を出すか」「MVPの範囲を決めたい」「架電SaaSのER図とAPI仕様がほしい」といった話題が出たら必ずこの skill を参照する。特定ベンダー（Twilio 等）を前提に実装コードを書く段階には使わない — その場合は outbound-calling-saas を使う。着信中心のコンタクトセンター（IVR・ACD・問い合わせ対応）や、Amazon Connect / Genesys を採用する場合にも使わない。
---

# 架電特化型SaaS 開発Skill

## 目的

営業、テレアポ、契約更新、督促、顧客フォロー等のアウトバウンドコール業務を対象に、
「顧客リスト → 発信 → 通話 → 録音 → 文字起こし → AI要約 → 架電結果 → 再架電 → CRM連携 → KPI分析」
を一元化する架電特化型SaaSの設計・開発を支援する。

## 同梱ファイル

必要になった時点で読む。最初から全部読む必要はない。

| ファイル | 読むとき |
| --- | --- |
| `references/architecture.md` | 構成を提案する前。層の境界（Domain 層が電話・CRM・AI ベンダーの SDK に依存しない）を確認する |
| `references/data-model.md` | ER 図やテーブル設計を書く前。主要エンティティと通話状態遷移（QUEUED → DIALING → RINGING → ANSWERED → COMPLETED と異常系）の定義を揃える |
| `templates/requirements-template.md` | 要件定義を起こすとき。この 16 項目を埋める形で進めると、架電件数・同意状態・録音要件といった後から効いてくる項目の聞き漏らしを防げる |

## 想定技術スタック

- Frontend: Next.js / React / TypeScript
- Backend: FastAPI / Python
- Database: PostgreSQL
- Cache / Queue: Redis（必要時）
- Telephony: 電話API / SIP / VoIP / CTI
- Realtime: WebSocket
- AI: Speech-to-Text + LLM
- CRM: Salesforce / HubSpot 等
- Auth: OAuth 2.0 / OIDC / JWT
- Billing: Stripe 等
- Infrastructure: Docker / AWS
- CI/CD: GitHub Actions 等

## このSkillを使用する場面

以下の依頼を受けた場合に使用する。

1. 架電SaaSの要件定義
2. CTI/電話APIを利用した発信機能の設計
3. 顧客・架電リスト管理画面の設計
4. Click to Call / オートコールの実装
5. 通話録音・録音管理
6. 音声文字起こし
7. LLMによる通話要約・顧客ニーズ抽出
8. 商談確度・次回アクション生成
9. 再架電スケジュール管理
10. CRM/SFA連携
11. 架電KPIダッシュボード
12. マルチテナントSaaS設計
13. Stripe等を利用したSaaS課金
14. セキュリティ・監査ログ・権限管理
15. Docker/AWSへのデプロイ
16. 単体・結合・E2Eテスト

## 基本業務フロー

```text
顧客/見込み客
    ↓
架電リスト
    ↓
担当者割当
    ↓
Click to Call / Auto Call
    ↓
電話API / SIP / VoIP
    ↓
通話
    ├─ 通話ステータス
    ├─ 録音
    └─ 通話時間
    ↓
Speech-to-Text
    ↓
LLM
    ├─ 要約
    ├─ ニーズ抽出
    ├─ NG/懸念事項抽出
    ├─ 商談確度
    ├─ 次回アクション
    └─ 推奨トーク
    ↓
架電結果保存
    ↓
CRM/SFA同期
    ↓
KPI分析
```

## 必須機能

### 1. テナント・ユーザー管理
- tenant
- user
- role
- permission
- organization
- team
- operator

原則として全業務データに `tenant_id` を持たせる。
他テナントのデータを取得できないことをAPI・DB双方で保証する。

### 2. 顧客管理
最低限以下を管理する。

- customer_id
- company_name
- customer_name
- phone_number
- email
- status
- owner_id
- tags
- consent/status metadata
- created_at
- updated_at

電話番号は表示用と正規化済み番号を分離する。

### 3. 架電リスト
- campaign
- call_list
- call_target
- priority
- assigned_operator
- scheduled_at
- retry_count
- last_call_at
- next_call_at

CSVインポート時は重複・電話番号形式・必須項目を検証する。

### 4. 発信
段階的に実装する。

#### Phase 1
Click to Call

#### Phase 2
順次自動発信

#### Phase 3
条件付き自動発信・高度なダイヤリング

自動発信は、法令、契約条件、電話事業者の利用規約、顧客の同意・拒否状態を確認した上で設計する。
大量発信・迷惑電話を助長する実装は行わない。

### 5. 通話管理
`call_session` を中心に管理する。

推奨項目:
- call_id
- tenant_id
- customer_id
- operator_id
- provider_call_id
- direction
- started_at
- answered_at
- ended_at
- duration_seconds
- result_code
- recording_id
- transcript_status
- ai_analysis_status

Webhookは冪等性を保証し、同一イベントの再送で二重登録しない。

### 6. 録音
録音データ自体とメタデータを分離する。

- object storageに音声
- DBにrecording metadata
- 保存期間を設定
- アクセス権を制限
- ダウンロード・再生操作を監査
- 必要に応じて暗号化

録音に関する告知・同意・保存要件は、利用地域・用途・契約条件に応じて確認する。

### 7. 文字起こし
非同期処理を基本とする。

```text
Call Completed
  ↓
Recording Ready
  ↓
Queue
  ↓
Speech-to-Text
  ↓
Transcript
  ↓
AI Analysis
```

文字起こし失敗時はretry可能にする。

### 8. AI通話分析
LLMには原則として構造化JSONを返させる。

例:

```json
{
  "summary": "料金に課題を感じており、次回更新時に比較検討予定",
  "needs": ["料金削減", "運用負荷削減"],
  "objections": ["導入コスト"],
  "sentiment": "neutral",
  "opportunity_score": 72,
  "next_action": {
    "type": "callback",
    "recommended_at": "2026-09-03T14:00:00+09:00"
  },
  "recommended_talk": "現行サービスとの費用比較を提示する"
}
```

AI判定は確定情報として扱わず、必要に応じて担当者が修正・承認できるUIを提供する。

### 9. 架電結果
標準結果コード例:

- CONNECTED
- NO_ANSWER
- BUSY
- VOICEMAIL
- CALLBACK
- APPOINTMENT
- NOT_INTERESTED
- DO_NOT_CALL
- INVALID_NUMBER
- OTHER

`DO_NOT_CALL` は特に重要な状態として扱い、誤再架電を防止する。

### 10. 再架電
- next_call_at
- reason
- assigned_operator
- reminder
- completed_at

タイムゾーンを明示して保存・表示する。

### 11. CRM連携
CRMとの同期はadapter層で分離する。

```text
Domain
  ↓
CRM Port
  ├─ SalesforceAdapter
  ├─ HubSpotAdapter
  └─ OtherCRMAdapter
```

外部CRM固有のデータモデルをドメインモデルへ直接侵入させない。
Anti-Corruption Layerを利用する。

### 12. KPI
最低限以下を計測する。

- 架電数
- 接続数
- 接続率
- 有効会話数
- アポイント数
- アポイント率
- 商談数
- 契約数
- 平均通話時間
- オペレーター別成果
- キャンペーン別成果
- 時間帯別成果

分母・分子を明示し、KPI定義を固定する。

## 推奨API

```text
POST   /api/v1/customers
GET    /api/v1/customers
GET    /api/v1/customers/{id}

POST   /api/v1/campaigns
GET    /api/v1/campaigns/{id}

POST   /api/v1/calls
GET    /api/v1/calls/{id}
POST   /api/v1/calls/{id}/complete

GET    /api/v1/calls/{id}/recording
GET    /api/v1/calls/{id}/transcript
GET    /api/v1/calls/{id}/analysis

POST   /api/v1/callbacks
PATCH  /api/v1/callbacks/{id}

GET    /api/v1/kpi/summary

POST   /api/v1/webhooks/telephony
POST   /api/v1/webhooks/billing
```

## 推奨DB

主要テーブル:

```text
tenants
users
roles
customers
campaigns
call_lists
call_targets
call_sessions
call_events
recordings
transcripts
ai_analyses
callbacks
do_not_call_entries
crm_sync_jobs
audit_logs
subscriptions
```

すべての外部IDにunique制約を検討する。
WebhookイベントIDも保存し、重複処理を防ぐ。

## Frontend画面

最低限以下を設計する。

1. ログイン
2. ダッシュボード
3. 顧客一覧
4. 顧客詳細
5. 架電リスト
6. 架電オペレーター画面
7. 通話履歴
8. 録音・文字起こし画面
9. AI分析画面
10. 再架電予定
11. キャンペーン管理
12. KPI分析
13. CRM連携設定
14. ユーザー・権限管理
15. SaaS契約・請求
16. 監査ログ

## オペレーター画面

1画面で以下を確認できること。

```text
┌─────────────────────────────┐
│ 顧客情報                     │
├──────────────┬──────────────┤
│ 会社名       │ ABC株式会社   │
│ 担当者       │ 田中様        │
│ 電話         │ 03-xxxx-xxxx  │
├──────────────┴──────────────┤
│ 過去の架電履歴               │
├─────────────────────────────┤
│ 営業スクリプト / AI支援      │
├─────────────────────────────┤
│ [発信] [保留] [終了]         │
├─────────────────────────────┤
│ 架電結果                     │
│ ○アポ ○再架電 ○不在 ○拒否  │
└─────────────────────────────┘
```

## セキュリティ

必須確認事項:

- TLS
- 認証・認可
- RBAC
- tenant isolation
- secrets management
- PII masking
- encryption at rest
- audit log
- webhook signature verification
- rate limiting
- CSRF/XSS/SQL injection対策
- dependency scanning
- backup/restore
- retention policy
- least privilege

秘密情報、APIキー、アクセストークンをソースコードへ埋め込まない。

## 法令・コンプライアンス

このSkillは法的判断を代替しない。
実サービス化前に対象地域・業種について専門家または担当部門の確認を行う。

特に確認するもの:
- 個人情報・プライバシー
- 通話録音
- 営業電話・勧誘
- オプトアウト / Do Not Call
- 発信者番号
- 電気通信関連制度
- AIによる自動応答・自動発信
- データ保存地域
- 外部AI/音声APIへのデータ送信

## 非機能要件

- API可用性
- 通話イベント取りこぼし防止
- Webhook retry
- 非同期ジョブ再実行
- DB backup
- observability
- structured logging
- tracing
- metrics
- alerting
- disaster recovery
- multi-AZ（必要規模に応じる）

## 実装原則

### 1. 外部電話サービスを抽象化する

```python
class TelephonyProvider:
    async def create_call(self, request):
        raise NotImplementedError

    async def get_recording(self, call_id):
        raise NotImplementedError
```

特定ベンダーのSDKをドメイン層から直接呼び出さない。

### 2. AIを非同期化する
電話終了APIのレスポンス内で文字起こし・LLM処理を完結させない。

### 3. Webhookを信頼しすぎない
署名検証、timestamp検証、idempotencyを実装する。

### 4. AI出力を検証する
Pydantic等でJSON schema validationを行う。

### 5. 人間による確認経路を残す
AIの要約・スコア・次回アクションを編集可能にする。

## テスト

### Unit
- customer service
- call state transition
- KPI calculation
- AI parser
- CRM mapper

### Integration
- PostgreSQL
- telephony adapter mock
- webhook
- transcription job
- CRM sync
- billing webhook

### E2E
- login
- customer import
- call start
- simulated call completion
- transcript
- AI analysis
- callback registration
- dashboard reflection

実電話への発信はテスト環境・許可された番号でのみ行う。

## 開発フェーズ

### Phase 1: MVP
- 認証
- 顧客管理
- CSV取込
- Click to Call
- 通話履歴
- 架電結果
- 再架電
- KPI

### Phase 2: AI
- 録音
- 文字起こし
- AI要約
- ニーズ抽出
- 次回アクション

### Phase 3: SaaS
- マルチテナント
- RBAC
- CRM
- Stripe等の課金
- 監査ログ

### Phase 4: 高度化
- リアルタイムAI支援
- 高度なダイヤリング
- RAG営業支援
- 品質評価
- 高度な分析

## Claude Code等への指示方法

ユーザーが「架電SaaSを作って」と依頼した場合、いきなり全機能を生成せず以下の順序で進める。

1. 要件整理
2. MVP範囲確定
3. アーキテクチャ
4. ER図
5. API仕様
6. 画面一覧
7. ディレクトリ構成
8. Docker開発環境
9. DB migration
10. Backend
11. Frontend
12. Telephony adapter
13. Webhook
14. AI pipeline
15. CRM
16. Billing
17. Tests
18. CI/CD
19. Security review
20. Deployment

## 成果物

要求に応じて以下を生成する。

- requirements.md
- architecture.md
- er-diagram.md
- api-spec.yaml
- security.md
- test-plan.md
- docker-compose.yml
- backend/
- frontend/
- migrations/
- tests/
- README.md

## 完了条件

最低限以下を満たすこと。

- tenant分離がテストされている
- 発信状態遷移が追跡できる
- Webhookが冪等
- DNC顧客へ誤発信しない
- 録音アクセスが認可される
- AI処理失敗から再実行可能
- CRM障害が架電本体を停止させない
- KPI計算にテストがある
- 監査ログがある
- 秘密情報がリポジトリに存在しない
