package com.kadensaas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kadensaas.domain.CallSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CallSessionRepository extends TenantScopedRepository<CallSession, UUID> {

    Optional<CallSession> findByProviderCallSid(String providerCallSid);

    Page<CallSession> findAllByOrderByStartedAtDesc(Pageable pageable);

    List<CallSession> findByCustomerIdOrderByStartedAtDesc(UUID customerId);

    /**
     * 進行中の通話があるか。
     *
     * <p>これは「先に分かりやすいエラーを返す」ためのもので、砦ではない。
     * 同時に 2 本の発信要求が来たときの競合はここでは防げないので、
     * 本当の防御は call_sessions の部分ユニークインデックスが担う。
     */
    @Query("select count(c) from CallSession c where c.toE164 = :e164 and c.dialStateRank < 90")
    long countInFlightTo(@Param("e164") String e164);
}
