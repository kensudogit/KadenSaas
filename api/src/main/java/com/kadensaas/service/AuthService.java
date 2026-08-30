package com.kadensaas.service;

import java.util.Optional;
import java.util.UUID;

import com.kadensaas.domain.Tenant;
import com.kadensaas.domain.UserAccount;
import com.kadensaas.repository.TenantRepository;
import com.kadensaas.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ログインのための 2 段階の問い合わせ。
 *
 * <p>★ なぜ 2 つのメソッドに分けてあるのか。
 * テナントは {@code SET LOCAL app.tenant_id} でトランザクション開始時に
 * 注入される（{@code TenantAwareTransactionManager}）。つまり
 * <b>トランザクションが始まった後に TenantContext を書き換えても遅い</b>。
 *
 * <p>ログインは「まずテナントを特定し、次にそのテナントの利用者を引く」
 * という順序が要る。1 つの {@code @Transactional} メソッドに両方を書くと、
 * テナント未設定のまま始まったトランザクションで users を引くことになり、
 * RLS が 0 行を返す。パスワードが正しくても必ず失敗する。
 *
 * <p>そこで問い合わせを 2 回に分け、間で TenantContext を設定する。
 * 呼び出し側（AuthController）は自分では {@code @Transactional} を持たない。
 *
 * <p>★ この分割は「別クラスにする」ことに意味がある。同じクラス内の
 * メソッドを呼んでも Spring のプロキシを通らず、新しいトランザクションが
 * 始まらないため。
 */
@Service
public class AuthService {

    private final TenantRepository tenants;
    private final UserAccountRepository users;

    public AuthService(TenantRepository tenants, UserAccountRepository users) {
        this.tenants = tenants;
        this.users = users;
    }

    /**
     * slug からテナントを引く。
     *
     * <p>★ tenants には RLS を掛けていない（tenant_id 列が無い）ので、
     * テナント未設定のトランザクションでも引ける。ここが
     * 「テナントを知る前に読める唯一のテーブル」になる。
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<Tenant> findTenantBySlug(String slug) {
        return tenants.findActiveBySlug(slug);
    }

    /**
     * テナント内の利用者を引く。
     *
     * <p>★ 呼ぶ前に {@code TenantContext.set(tenantId)} を済ませておくこと。
     * REQUIRES_NEW にしてあるのは、呼び出し時点で新しいトランザクションを
     * 開かせ、そこで app.tenant_id を注入させるため。
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<UserAccount> findActiveUser(UUID tenantId, String email) {
        return users.findActiveByEmail(tenantId, email);
    }

    /** 最終ログイン時刻の更新。失敗してもログインは通す。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchLastSeen(UUID userId) {
        users.findById(userId).ifPresent(u -> {
            u.setLastSeenAt(java.time.OffsetDateTime.now());
            users.save(u);
        });
    }
}
