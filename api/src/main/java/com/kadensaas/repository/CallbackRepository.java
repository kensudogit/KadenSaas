package com.kadensaas.repository;

import java.util.List;
import java.util.UUID;

import com.kadensaas.domain.Callback;

public interface CallbackRepository extends TenantScopedRepository<Callback, UUID> {

    List<Callback> findByStatusOrderByScheduledAtAsc(String status);
}
