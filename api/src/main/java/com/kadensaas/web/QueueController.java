package com.kadensaas.web;

import java.util.Map;
import java.util.UUID;

import com.kadensaas.service.CallQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 架電キュー。オペレーターが「次の 1 件」を受け取る。
 *
 * <p>★ 予約（reserve）にして即座に他人から見えなくする。
 * 「一覧から選ばせる」方式にすると、同じ相手を 2 人が同時に開き、
 * 同じ人に 2 回かかる。取り合いは DB の 1 文で解決する。
 *
 * <p>★ 予約には期限を付ける。ブラウザが落ちた・タブを閉じた場合に
 * 解放されないと、コールリストが少しずつ枯れていく。数日かけて
 * 静かに減るので、気付いたときには原因の特定が難しい。
 * 期限切れは定期ジョブが戻す。
 */
@RestController
@RequestMapping("/api/v1/queue")
public class QueueController {

    private final CallQueue queue;
    private final JdbcTemplate jdbc;
    private final int reservationTtlSeconds;

    public QueueController(CallQueue queue, JdbcTemplate jdbc,
                           @Value("${kaden.queue.reservation-ttl-seconds:600}") int ttl) {
        this.queue = queue;
        this.jdbc = jdbc;
        this.reservationTtlSeconds = ttl;
    }

    @PostMapping("/next")
    @Transactional
    public Map<String, Object> next(@RequestParam UUID campaignId) {
        var user = CurrentUser.require();

        UUID targetId = queue.reserveNext(campaignId, user.userId(), reservationTtlSeconds);
        if (targetId == null) {
            return Map.of("available", false);
        }

        // ★ 発信に必要な情報をまとめて返す。オペレーター画面が
        //   1 画面で完結するために、ここで顧客・番号・履歴を揃える
        var row = jdbc.queryForMap("""
            select t.id as target_id, t.customer_id, t.phone_id,
                   c.company_name, c.contact_name, c.note,
                   p.raw_number, p.e164,
                   t.attempts, t.last_attempt_at,
                   exists(select 1 from do_not_call_entries d
                           where d.e164 = p.e164) as is_dnc
              from call_targets t
              join customers c on c.id = t.customer_id
              join customer_phones p on p.id = t.phone_id
             where t.id = ?
            """, targetId);

        var history = jdbc.queryForList("""
            select s.started_at, s.dial_state, s.disposition_code, s.duration_seconds,
                   d.label as disposition_label
              from call_sessions s
              left join disposition_codes d on d.code = s.disposition_code
             where s.customer_id = ?
             order by s.started_at desc
             limit 10
            """, row.get("customer_id"));

        return Map.of("available", true, "target", row, "history", history);
    }

    /** 予約を手放す。かけずに次へ進むとき。 */
    @PostMapping("/{targetId}/release")
    public Map<String, Object> release(@PathVariable UUID targetId) {
        queue.release(targetId);
        return Map.of("ok", true);
    }
}
