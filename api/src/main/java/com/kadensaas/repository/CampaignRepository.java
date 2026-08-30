package com.kadensaas.repository;

import java.util.List;
import java.util.UUID;

import com.kadensaas.domain.Campaign;

public interface CampaignRepository extends TenantScopedRepository<Campaign, UUID> {

    List<Campaign> findByStatusOrderByCreatedAtDesc(String status);
}
