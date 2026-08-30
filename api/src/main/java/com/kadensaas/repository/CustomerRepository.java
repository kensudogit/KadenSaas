package com.kadensaas.repository;

import java.util.List;
import java.util.UUID;

import com.kadensaas.domain.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * where tenant_id を書いていないのは、RLS が効いているため。
 *
 * <p>アプリの where に頼る設計にすると、1 箇所の書き忘れが漏洩になる。
 * ここでは「書かなくても漏れない」ことを前提にし、その前提は
 * scripts/verify-schema.sql と RlsIsolationTest が毎回確かめる。
 */
public interface CustomerRepository extends TenantScopedRepository<Customer, UUID> {

    Page<Customer> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("select c from Customer c where lower(c.companyName) like lower(concat('%', :q, '%')) "
         + "or lower(c.contactName) like lower(concat('%', :q, '%'))")
    List<Customer> search(@Param("q") String q, Pageable pageable);
}
