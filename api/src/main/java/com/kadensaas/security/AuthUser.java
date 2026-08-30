package com.kadensaas.security;

import java.util.UUID;

/**
 * 認証済みの利用者。JWT から復元する。
 *
 * <p>★ tenantId をここに持たせ、フィルタが {@code TenantContext} へ渡す。
 * リクエストパラメータやヘッダから受け取ってはいけない。受け取ると
 * 「他人の tenantId を送れば他人のデータが見える」になる。
 * 署名されたトークンの中にある値だけを信用する。
 */
public record AuthUser(UUID userId, UUID tenantId, String email, Role role) {

    public enum Role {
        OPERATOR, MANAGER, ADMIN;

        public static Role of(String raw) {
            return Role.valueOf(raw.toUpperCase());
        }

        /** manager は operator を包含し、admin は両方を包含する。 */
        public boolean atLeast(Role required) {
            return this.ordinal() >= required.ordinal();
        }
    }

    public boolean isManager() {
        return role.atLeast(Role.MANAGER);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
