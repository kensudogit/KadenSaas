# Reference Architecture

```text
[Next.js / React]
       |
       v
[FastAPI API]
  |    |     |
  |    |     +--> [CRM Adapter] --> CRM/SFA
  |    +--------> [PostgreSQL]
  +-------------> [Telephony Adapter] --> Phone API/SIP
                       |
                       v
                   Webhooks
                       |
                       v
                  [Job Queue]
                    /     \
                   v       v
              Speech-to-Text  LLM
                   \       /
                    v     v
                  AI Analysis
```

## Boundary
- Domain層は電話・CRM・AIベンダーSDKに依存しない。
- 外部サービスはAdapter経由で利用する。
- WebhookとAI処理は再実行可能にする。
