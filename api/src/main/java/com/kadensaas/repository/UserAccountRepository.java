package com.kadensaas.repository;

import java.util.Optional;
import java.util.UUID;

import com.kadensaas.domain.UserAccount;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends TenantScopedRepository<UserAccount, UUID> {

    @Query("select u from UserAccount u where u.tenantId = :tenantId "
         + "and lower(u.email) = lower(:email) and u.status = 'active'")
    Optional<UserAccount> findActiveByEmail(@Param("tenantId") UUID tenantId,
                                            @Param("email") String email);
}
