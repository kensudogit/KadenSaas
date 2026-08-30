package com.kadensaas.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "callbacks")
@Getter
@Setter
public class Callback {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "call_session_id")
    private UUID callSessionId;

    /** ★ タイムゾーン付きで保持する。架電時間帯の判定がここに依存する。 */
    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    private String reason;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    private String status;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
