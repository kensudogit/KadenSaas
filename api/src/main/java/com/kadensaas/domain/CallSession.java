package com.kadensaas.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 1 回の通話。
 *
 * <p>★ dialState（機械が書く技術的な状態）と dispositionCode（人／AI が書く
 * 業務結果）を別の列に持つ。1 本の status に混ぜると
 * 「通話は終わったが文字起こしは処理中」が表現できず、どちらかを潰す。
 *
 * <p>★ dialStateRank は生成列で、読み取り専用。更新のたびに
 * {@code where dial_state_rank < :newRank} を付けることで、
 * 順不同で届く webhook による巻き戻りを DB 側で弾く。
 */
@Entity
@Table(name = "call_sessions")
@Getter
@Setter
public class CallSession {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Column(name = "call_target_id")
    private UUID callTargetId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "operator_id")
    private UUID operatorId;

    private String provider;

    /** ★ 通話の同一性はこれで担保する。Twilio の CallSid。 */
    @Column(name = "provider_call_sid")
    private String providerCallSid;

    private String direction;

    @Column(name = "from_e164")
    private String fromE164;

    @Column(name = "to_e164")
    private String toE164;

    @Column(name = "dial_state")
    private String dialState;

    /** ★ 生成列。アプリからは書かない。 */
    @Column(name = "dial_state_rank", insertable = false, updatable = false)
    private Integer dialStateRank;

    @Column(name = "disposition_code")
    private String dispositionCode;

    @Column(name = "blocked_reason")
    private String blockedReason;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "answered_at")
    private OffsetDateTime answeredAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
