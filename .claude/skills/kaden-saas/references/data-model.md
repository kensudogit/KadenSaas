# Core Data Model

主要エンティティ:
- Tenant
- User
- Customer
- Campaign
- CallTarget
- CallSession
- CallEvent
- Recording
- Transcript
- AIAnalysis
- Callback
- DoNotCallEntry
- AuditLog

## Call state example
QUEUED -> DIALING -> RINGING -> ANSWERED -> COMPLETED

異常系:
- BUSY
- NO_ANSWER
- FAILED
- CANCELED
