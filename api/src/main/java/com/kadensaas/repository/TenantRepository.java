package com.kadensaas.repository;

import java.util.Optional;
import java.util.UUID;

import com.kadensaas.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * tenants には RLS を掛けていない（tenant_id 列が無いため）。
 *
 * <p>その代わり slug か id の完全一致でしか引けないメソッドしか置かない。
 * 一覧を返すメソッドを足すと、そこから他テナントの存在が漏れる。
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    @Query("select t from Tenant t where lower(t.slug) = lower(:slug) and t.status = 'active'")
    Optional<Tenant> findActiveBySlug(@Param("slug") String slug);
}
