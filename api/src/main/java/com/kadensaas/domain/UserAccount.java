package com.kadensaas.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 利用者。
 *
 * <p>★ クラス名を User にしないのは、PostgreSQL の予約語 user との衝突と、
 * Spring Security の User との取り違えを避けるため。テーブル名は users。
 */
@Entity
@Table(name = "users")
@Getter
@Setter
public class UserAccount {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    private String role;
    private String status;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
