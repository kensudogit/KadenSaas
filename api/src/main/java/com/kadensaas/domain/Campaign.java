package com.kadensaas.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "campaigns")
@Getter
@Setter
public class Campaign {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    private String name;
    private String script;
    private String status;

    @Column(name = "ai_agent_enabled", nullable = false)
    private boolean aiAgentEnabled;

    @Column(name = "auto_dialer_enabled", nullable = false)
    private boolean autoDialerEnabled;

    @Column(name = "auto_dialer_interval_seconds", nullable = false)
    private int autoDialerIntervalSeconds = 45;

    @Column(name = "ivr_enabled", nullable = false)
    private boolean ivrEnabled;

    @Column(name = "ivr_welcome_text")
    private String ivrWelcomeText;

    @Column(name = "ivr_menu_json", columnDefinition = "jsonb")
    private String ivrMenuJson;

    @Column(name = "starts_at")
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
