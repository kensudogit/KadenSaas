package com.kadensaas.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 再勧誘拒否。発信の関門が必ず参照する。
 *
 * <p>★ 顧客ではなく番号に紐づける。顧客レコードを分割・統合しても、
 * 拒否の意思は番号に残らなければならない。
 */
@Entity
@Table(name = "do_not_call_entries")
@Getter
@Setter
public class DoNotCallEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    private String e164;
    private String reason;
    private String source;
    private String note;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
