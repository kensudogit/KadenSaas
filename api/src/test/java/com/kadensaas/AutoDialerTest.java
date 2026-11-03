package com.kadensaas;

import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.service.AiOutboundDemoService;
import com.kadensaas.service.AutoDialerService;
import com.kadensaas.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AutoDialerTest extends AbstractIntegrationTest {

    @Autowired
    AiOutboundDemoService demo;

    @Autowired
    AutoDialerService autoDialer;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("オートダイヤラー: 開始と tick が 500 にならない")
    void startAndTick() {
        UUID tenantId = createTenant("ad-demo", "ADデモ社");
        UUID userId = createUser(tenantId, "ad@example.com", "operator");
        TenantContext.set(tenantId);
        var user = new AuthUser(userId, tenantId, "ad@example.com", AuthUser.Role.OPERATOR);

        var seed = demo.seedAutoDialerIfEmpty(user);
        assertThat(seed.overview().campaignId()).isNotNull();

        var camps = demo.listAutoDialerCampaigns(user);
        assertThat(camps).hasSize(1);

        UUID campaignId = (UUID) camps.get(0).get("id");
        var run = autoDialer.start(user, campaignId, "ivr");
        assertThat(run.status()).isEqualTo("running");

        assertThatCode(() -> autoDialer.tick(user, run.id()))
            .doesNotThrowAnyException();

        var tick = autoDialer.tick(user, run.id());
        assertThat(tick.message()).isNotBlank();
    }

    @Test
    @DisplayName("listRuns は tenant_id で絞る")
    void listRunsTenantScoped() {
        UUID tenantId = createTenant("ad-list", "AD一覧社");
        UUID userId = createUser(tenantId, "list@example.com", "operator");
        TenantContext.set(tenantId);
        var user = new AuthUser(userId, tenantId, "list@example.com", AuthUser.Role.OPERATOR);

        demo.seedAutoDialerIfEmpty(user);
        UUID campaignId = (UUID) demo.listAutoDialerCampaigns(user).get(0).get("id");
        autoDialer.start(user, campaignId, "operator");

        assertThatCode(() -> autoDialer.listRuns(user)).doesNotThrowAnyException();
        assertThat(autoDialer.listRuns(user)).hasSize(1);
    }
}
