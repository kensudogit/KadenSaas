package com.kadensaas.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.security.AuthUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * キャンペーンキューを順次自動発信する（1 通話ずつ）。
 *
 * <p>★ 同時に 1 本だけ。in-flight の call_session がある間は tick をスキップする。
 */
@Service
public class AutoDialerService {

    public record RunView(
        UUID id,
        UUID campaignId,
        String campaignName,
        String conversationMode,
        String status,
        int intervalSeconds,
        int dialsAttempted,
        UUID lastCallSessionId) {
    }

    public record TickResult(
        boolean dialed,
        String skipReason,
        UUID callSessionId,
        UUID targetId,
        String companyName,
        String e164,
        String message) {
    }

    private final JdbcTemplate jdbc;
    private final CallQueue queue;
    private final CallService callService;
    private final VoiceDialClient voice;

    public AutoDialerService(JdbcTemplate jdbc, CallQueue queue,
                             CallService callService, VoiceDialClient voice) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.callService = callService;
        this.voice = voice;
    }

    @Transactional(readOnly = true)
    public List<RunView> listRuns(AuthUser user) {
        return jdbc.query("""
            select r.id, r.campaign_id, c.name, r.conversation_mode, r.status,
                   r.interval_seconds, r.dials_attempted, r.last_call_session_id
              from auto_dialer_runs r
              join campaigns c on c.id = r.campaign_id and c.tenant_id = r.tenant_id
             where r.status in ('running', 'paused')
             order by r.started_at desc
            """,
            (rs, n) -> new RunView(
                rs.getObject("id", UUID.class),
                rs.getObject("campaign_id", UUID.class),
                rs.getString("name"),
                rs.getString("conversation_mode"),
                rs.getString("status"),
                rs.getInt("interval_seconds"),
                rs.getInt("dials_attempted"),
                rs.getObject("last_call_session_id", UUID.class)),
            user.tenantId());
    }

    @Transactional
    public RunView start(AuthUser user, UUID campaignId, String conversationMode) {
        var camp = jdbc.queryForMap("""
            select id, name, auto_dialer_enabled, auto_dialer_interval_seconds
              from campaigns where id = ? and tenant_id = ?
            """, campaignId, user.tenantId());
        if (!Boolean.TRUE.equals(camp.get("auto_dialer_enabled"))) {
            throw new IllegalArgumentException("このキャンペーンはオートダイヤラーが有効ではありません");
        }
        String mode = ConversationMode.normalize(conversationMode);
        int interval = ((Number) camp.get("auto_dialer_interval_seconds")).intValue();

        UUID runId = jdbc.queryForObject("""
            insert into auto_dialer_runs
              (tenant_id, campaign_id, started_by, conversation_mode, interval_seconds)
            values (?, ?, ?, ?, ?)
            returning id
            """, UUID.class, user.tenantId(), campaignId, user.userId(), mode, interval);

        return new RunView(
            runId, campaignId, (String) camp.get("name"), mode, "running",
            interval, 0, null);
    }

    @Transactional
    public void pause(UUID runId) {
        jdbc.update("""
            update auto_dialer_runs set status = 'paused', last_tick_at = now()
             where id = ? and status = 'running'
            """, runId);
    }

    @Transactional
    public void resume(UUID runId) {
        jdbc.update("""
            update auto_dialer_runs set status = 'running', last_tick_at = now()
             where id = ? and status = 'paused'
            """, runId);
    }

    @Transactional
    public void stop(UUID runId) {
        jdbc.update("""
            update auto_dialer_runs
               set status = 'stopped', stopped_at = now(), last_tick_at = now()
             where id = ? and status <> 'stopped'
            """, runId);
    }

    @Transactional
    public TickResult tick(AuthUser user, UUID runId) {
        Map<String, Object> run = jdbc.queryForMap("""
            select r.id, r.campaign_id, r.conversation_mode, r.status
              from auto_dialer_runs r
             where r.id = ? and r.tenant_id = ?
            """, runId, user.tenantId());

        if (!"running".equals(run.get("status"))) {
            return new TickResult(false, "not_running", null, null, null, null,
                "ランは停止または一時停止中です");
        }

        UUID campaignId = (UUID) run.get("campaign_id");
        if (countInflight(campaignId) > 0) {
            jdbc.update("update auto_dialer_runs set last_tick_at = now() where id = ?", runId);
            return new TickResult(false, "in_flight", null, null, null, null,
                "前の通話が終わるまで待機します");
        }

        UUID targetId = queue.reserveNext(campaignId, user.userId(), 600);
        if (targetId == null) {
            stop(runId);
            return new TickResult(false, "queue_empty", null, null, null, null,
                "キューが空のためオートダイヤルを停止しました");
        }

        var target = jdbc.queryForMap("""
            select t.phone_id, c.company_name, p.e164
              from call_targets t
              join customers c on c.id = t.customer_id
              join customer_phones p on p.id = t.phone_id
             where t.id = ?
            """, targetId);

        String mode = (String) run.get("conversation_mode");
        var dial = callService.requestDial(
            user,
            (UUID) target.get("phone_id"),
            campaignId,
            targetId,
            mode);

        if (!dial.accepted()) {
            queue.release(targetId);
            return new TickResult(false, dial.blockedReason(), null, targetId,
                (String) target.get("company_name"),
                (String) target.get("e164"),
                dial.message());
        }

        try {
            voice.dialQueuedSession(user, dial.callSessionId());
        } catch (RuntimeException e) {
            queue.release(targetId);
            return new TickResult(false, "voice_dial_failed", dial.callSessionId(),
                targetId, (String) target.get("company_name"),
                (String) target.get("e164"), e.getMessage());
        }

        jdbc.update("""
            update auto_dialer_runs
               set dials_attempted = dials_attempted + 1,
                   last_call_session_id = ?,
                   last_tick_at = now()
             where id = ?
            """, dial.callSessionId(), runId);

        return new TickResult(true, null, dial.callSessionId(), targetId,
            (String) target.get("company_name"),
            (String) target.get("e164"),
            "発信しました");
    }

    private int countInflight(UUID campaignId) {
        Integer n = jdbc.queryForObject("""
            select count(*) from call_sessions
             where campaign_id = ? and dial_state_rank < 90
            """, Integer.class, campaignId);
        return n == null ? 0 : n;
    }

}
