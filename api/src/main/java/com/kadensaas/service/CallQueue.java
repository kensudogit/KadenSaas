package com.kadensaas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 架電キューの予約と解放。
 *
 * <p>★ JPA ではなく JdbcTemplate を使っている。予約は
 * {@code UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING id}
 * という 1 文で原子的に行う必要があり、Spring Data の {@code @Modifying} は
 * 戻り値に void / int しか許さないため UUID を返せない
 * （実際に {@code IllegalArgumentException} で落ちた）。
 * ここで JPA に寄せる利点は無い。
 *
 * <p>★ select してから update に分けない。同時に画面を開いた 2 人が
 * 同じ相手を掴み、同じ人に 2 回かかる。1 文にすることでしか防げない。
 *
 * <p>★ JdbcTemplate は呼び出し元の Spring トランザクションに参加するので、
 * {@code app.tenant_id} は設定済みの接続で走る。逆に言えば、
 * {@code @Transactional} の外から呼ぶと RLS で 0 行になる。
 */
@Service
public class CallQueue {

    private final JdbcTemplate jdbc;

    public CallQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 次の 1 件を予約する。取れなければ null。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} により、競合した側は待たずに
     * 次の候補へ進む。待たせると、同時に開いた人数ぶん画面が固まる。
     */
    @Transactional
    public UUID reserveNext(UUID campaignId, UUID operatorId, int ttlSeconds) {
        List<UUID> ids = jdbc.query(
            """
            update call_targets
               set state = 'reserved',
                   assigned_to = ?,
                   reserved_until = now() + make_interval(secs => ?),
                   updated_at = now()
             where id = (
               select t.id from call_targets t
                where t.campaign_id = ?
                  and t.state = 'pending'
                  and (t.next_attempt_at is null or t.next_attempt_at <= now())
                order by t.priority, t.next_attempt_at nulls first
                limit 1
                for update skip locked)
            returning id
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            operatorId, ttlSeconds, campaignId);

        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 予約を手放す。かけずに次へ進むとき。 */
    @Transactional
    public void release(UUID targetId) {
        jdbc.update("""
            update call_targets
               set state = 'pending', assigned_to = null,
                   reserved_until = null, updated_at = now()
             where id = ? and state = 'reserved'
            """, targetId);
    }

    /**
     * 期限切れの予約を戻す。
     *
     * <p>★ これを定期的に動かさないと、担当者のブラウザが落ちるたびに
     * 予約が残り、コールリストが少しずつ枯れる。数日かけて静かに減るので、
     * 気付いたときには原因の特定が難しい。
     *
     * <p>★ テナントをまたいで実行する必要があるため、RLS を効かせたまま
     * では動かない。定期ジョブは kaden_migrator（BYPASSRLS）で動かすか、
     * テナントごとに回す。ここでは後者を前提に、呼び出し側が
     * テナントを設定してから呼ぶ。
     */
    @Transactional
    public int releaseExpired() {
        return jdbc.update("""
            update call_targets
               set state = 'pending', assigned_to = null,
                   reserved_until = null, updated_at = now()
             where state = 'reserved' and reserved_until < now()
            """);
    }
}
