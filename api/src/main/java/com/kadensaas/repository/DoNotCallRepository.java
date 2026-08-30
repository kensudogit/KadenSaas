package com.kadensaas.repository;

import java.util.UUID;

import com.kadensaas.domain.DoNotCallEntry;

public interface DoNotCallRepository extends TenantScopedRepository<DoNotCallEntry, UUID> {

    /** 関門が呼ぶ。E.164 正規化済みの値でしか照合しない。 */
    boolean existsByE164(String e164);

    void deleteByE164(String e164);
}
