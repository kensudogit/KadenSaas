package com.kadensaas.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 架電すべき相手の 1 行。
 *
 * <p>★ CallSession（1 回の通話）とは 1 対多。ここを 1 対 1 にすると
 * 再架電が表現できなくなる。
 *
 * <p>★ reservedUntil は予約の期限。オペレーターのブラウザが落ちたときに
 * 解放されないと、リストが少しずつ枯れていく。定期ジョブが期限切れを戻す。
 */
@Entity
@Table(name = "call_targets")
@Getter
@Setter
public class CallTarget {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "phone_id", nullable = false)
    private UUID phoneId;

    private int priority;
    private String state;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "reserved_until")
    private OffsetDateTime reservedUntil;

    private int attempts;

    @Column(name = "attempts_today")
    private int attemptsToday;

    @Column(name = "last_attempt_at")
    private OffsetDateTime lastAttemptAt;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
