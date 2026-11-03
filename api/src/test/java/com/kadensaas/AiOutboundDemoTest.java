package com.kadensaas;

import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.service.AiOutboundDemoService;
import com.kadensaas.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AiOutboundDemoTest extends AbstractIntegrationTest {

    @Autowired
    AiOutboundDemoService aiDemo;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("AI 架電サンプルは冪等に登録され一覧に出る")
    void seedAndOverview() {
        UUID tenantId = createTenant("ai-demo", "AIデモ社");
        UUID userId = createUser(tenantId, "ai@example.com", "operator");
        TenantContext.set(tenantId);
        var user = new AuthUser(userId, tenantId, "ai@example.com", AuthUser.Role.OPERATOR);

        assertThat(aiDemo.overview(user).campaignId()).isNull();

        var first = aiDemo.seedIfEmpty(user);
        assertThat(first.created()).isTrue();
        assertThat(first.overview().targets()).hasSize(5);
        assertThat(first.overview().pendingCount()).isEqualTo(5);
        assertThat(first.overview().script()).contains("KadenSaas");

        var second = aiDemo.seedIfEmpty(user);
        assertThat(second.created()).isFalse();
        assertThat(second.overview().targets()).hasSize(5);
    }
}
