package com.kadensaas.tenant;

import java.util.UUID;

/**
 * いま処理しているリクエストのテナント。
 *
 * <p>★ ここに入れた値は {@link TenantAwareTransactionManager} が
 * トランザクション開始時に PostgreSQL の {@code app.tenant_id} へ流し込み、
 * RLS のポリシーがそれを読む。アプリ側の where 句には一切依存しない。
 * where を 1 箇所書き忘れただけで他テナントのデータが漏れ、しかも
 * テストは通ってしまうため。
 *
 * <p>★ 値が入っていない状態は「全部見える」ではなく「1 行も見えない」。
 * ポリシーが {@code tenant_id = app_current_tenant()} で、未設定なら
 * null になるため、比較が成立しない。fail closed であることが重要で、
 * 認証を通っていないリクエストが素通りしても情報は出ない。
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException(
                "テナントが未設定です。認証を通らない経路から業務データに触れています");
        }
        return id;
    }

    /**
     * ★ 必ずリクエストの終わりで呼ぶ。スレッドはプールで使い回されるので、
     * 消し忘れると次のリクエストが前のテナントの値を引き継ぐ。
     * これは「たまに他人のデータが見える」という最悪の壊れ方をする。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
