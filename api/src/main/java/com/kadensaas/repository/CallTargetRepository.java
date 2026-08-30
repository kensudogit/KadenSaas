package com.kadensaas.repository;

import java.util.UUID;

import com.kadensaas.domain.CallTarget;

/**
 * 架電対象。
 *
 * <p>★ 予約（reserve）と期限切れの解放は、単文の
 * {@code UPDATE ... RETURNING} で原子的に行う必要があるため、
 * ここには置かず {@code service.CallQueue} が JdbcTemplate で持つ。
 * Spring Data の {@code @Modifying} は戻り値に void / int しか許さず、
 * 予約した行の id を返せない。
 */
public interface CallTargetRepository extends TenantScopedRepository<CallTarget, UUID> {
}
