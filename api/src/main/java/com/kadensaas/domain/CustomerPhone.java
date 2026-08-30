package com.kadensaas.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 電話番号。
 *
 * <p>★ raw と e164 を分けて持つ。DNC 照合と重複検出は e164 でしか成立しない。
 * 03-1234-5678 と +81312345678 は同じ相手だが文字列としては別物で、
 * 表示用だけを持つと照合が素通りする。
 */
@Entity
@Table(name = "customer_phones")
@Getter
@Setter
public class CustomerPhone {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /** 入力されたままの表示用。ハイフンや内線表記を保つ。 */
    @Column(name = "raw_number")
    private String rawNumber;

    /** 照合・発信に使う唯一の値。 */
    private String e164;

    private String kind;

    @Column(name = "is_primary")
    private boolean primaryNumber;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
