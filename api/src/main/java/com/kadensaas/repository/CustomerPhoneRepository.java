package com.kadensaas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kadensaas.domain.CustomerPhone;

public interface CustomerPhoneRepository extends TenantScopedRepository<CustomerPhone, UUID> {

    List<CustomerPhone> findByCustomerId(UUID customerId);

    Optional<CustomerPhone> findByE164(String e164);
}
