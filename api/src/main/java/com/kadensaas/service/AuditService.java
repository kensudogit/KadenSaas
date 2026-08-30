package com.kadensaas.service;

import java.util.UUID;

import com.kadensaas.security.AuthUser;
import com.kadensaas.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 監査ログ。
 *
 * <p>★ 個人情報そのものを changes に入れない。「どの項目が変わったか」までにする。
 * 監査ログは長期保存され、権限も広めになりがちなので、そこに生の
 * 電話番号や氏名を溜めると保護対象が二重になる。
 *
 * <p>★ このテーブルはアプリロールから update / delete できない
 * （V6 で revoke してある）。消せる監査ログは監査にならない。
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(AuthUser user, String action, String entityType, UUID entityId) {
        record(user, action, entityType, entityId, null);
    }

    /**
     * ★ MANDATORY。監査ログを書く操作は必ず業務トランザクションの中で行う。
     * 別トランザクションにすると「業務は失敗したのに監査ログだけ残る」
     * （またはその逆）が起きて、記録が事実と食い違う。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuthUser user, String action, String entityType, UUID entityId,
                       String changesJson) {
        jdbc.update("""
            insert into audit_logs (tenant_id, user_id, action, entity_type, entity_id, changes)
            values (?, ?, ?, ?, ?, cast(? as jsonb))
            """,
            user != null ? user.tenantId() : TenantContext.get(),
            user != null ? user.userId() : null,
            action, entityType, entityId, changesJson);
    }
}
