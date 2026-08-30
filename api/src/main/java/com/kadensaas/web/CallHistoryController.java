package com.kadensaas.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kadensaas.security.AuthUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 架電履歴。発信日時・通話時間・結果・録音。
 *
 * <p>★ オペレーターには自分の通話だけを返す。担当者別の成績は評価に
 * 直結するので、互いの履歴が既定で見える状態にしない。manager 以上は全件。
 * この判定はサーバー側で行う。画面で出し分けるだけだと、API を直接
 * 叩けば他人の履歴が読める。
 *
 * <p>★ 止めた発信（blocked）も履歴に含める。「かけたが繋がらなかった」と
 * 「そもそもかけていない」は別物で、後者が見えないと
 * 「なぜ架電数が伸びないのか」が分からない。既定では含め、絞り込みで分けられる。
 *
 * <p>★ 録音は「ある／なし」だけをここで返す。再生 URL は voice 側が
 * 署名付きで発行する（S3 の資格情報を持つのは voice だけ）。
 * ここで URL を作ると、api にも保管先の鍵を持たせることになる。
 */
@RestController
// ★ /api/v1/calls/history にしない。CallController が /api/v1/calls/{id} を
//   持っているため、リテラルとパス変数のどちらが勝つかという知識を
//   読む人に要求することになる。曖昧さを避けて別の入口にする
@RequestMapping("/api/v1/call-history")
// ★ 必須。トランザクションの外で JdbcTemplate を使うと RLS が 0 行を返す
@Transactional(readOnly = true)
public class CallHistoryController {

    /** 1 ページの上限。指定が無い／大きすぎる場合に丸める。 */
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;

    public CallHistoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID operatorId,
            @RequestParam(required = false) String disposition,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "all") String kind,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {

        AuthUser user = CurrentUser.require();

        LocalDate start = from != null ? from : LocalDate.now().minusDays(29);
        LocalDate end = to != null ? to : LocalDate.now();
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        int skip = Math.max(0, offset);

        StringBuilder where = new StringBuilder(
            " where (cs.started_at at time zone t.timezone)::date between ? and ? ");
        List<Object> args = new ArrayList<>(List.of(start, end));

        // ★ オペレーターは自分の分だけ。画面ではなくここで絞る
        if (user.role() == AuthUser.Role.OPERATOR) {
            where.append(" and cs.operator_id = ? ");
            args.add(user.userId());
        } else if (operatorId != null) {
            where.append(" and cs.operator_id = ? ");
            args.add(operatorId);
        }

        switch (kind) {
            case "blocked" -> where.append(" and cs.dial_state = 'blocked' ");
            case "dialed" -> where.append(" and cs.dial_state <> 'blocked' ");
            case "recorded" -> where.append(
                " and exists (select 1 from recordings r"
                + " where r.call_session_id = cs.id and r.status = 'stored') ");
            default -> { /* all */ }
        }

        if (disposition != null && !disposition.isBlank()) {
            where.append(" and cs.disposition_code = ? ");
            args.add(disposition);
        }

        if (q != null && !q.isBlank()) {
            // 相手先番号か会社名で絞る
            where.append(" and (cs.to_e164 ilike ? or c.company_name ilike ?) ");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }

        String base = """
            from call_sessions cs
            join tenants t on t.id = cs.tenant_id
            left join customers c on c.id = cs.customer_id
            left join users u on u.id = cs.operator_id
            left join disposition_codes dc on dc.code = cs.disposition_code
            """;

        Integer total = jdbc.queryForObject(
            "select count(*) " + base + where, Integer.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(skip);

        var rows = jdbc.queryForList("""
            select cs.id,
                   cs.started_at,
                   cs.answered_at,
                   cs.ended_at,
                   cs.duration_seconds,
                   cs.dial_state,
                   cs.blocked_reason,
                   cs.to_e164,
                   cs.disposition_code,
                   dc.label            as disposition_label,
                   dc.is_success       as disposition_is_success,
                   cs.customer_id,
                   c.company_name,
                   cs.operator_id,
                   u.display_name      as operator_name,
                   -- ★ 録音の有無だけ。URL は voice が署名付きで出す
                   (select r.id from recordings r
                     where r.call_session_id = cs.id and r.status = 'stored'
                     order by r.created_at desc limit 1) as recording_id
            """ + base + where + " order by cs.started_at desc limit ? offset ?",
            pageArgs.toArray());

        return Map.of(
            "rows", rows,
            "total", total == null ? 0 : total,
            "offset", skip,
            "limit", size,
            // ★ 画面が「自分の分だけ表示中」と出せるようにする。
            //   黙って絞ると、件数が合わないという問い合わせになる
            "scopedToSelf", user.role() == AuthUser.Role.OPERATOR);
    }

    /**
     * 1 本の通話の詳細。結果の変更履歴を含む。
     *
     * <p>★ 最新の結果だけでなく履歴を返す。上書きだけにすると
     * 「誰がいつ何に変えたか」が消え、KPI の数字を後から説明できない。
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        AuthUser user = CurrentUser.require();

        var rows = jdbc.queryForList("""
            select cs.id, cs.started_at, cs.answered_at, cs.ended_at,
                   cs.duration_seconds, cs.dial_state, cs.blocked_reason,
                   cs.from_e164, cs.to_e164, cs.provider_call_sid,
                   cs.disposition_code, dc.label as disposition_label,
                   cs.customer_id, c.company_name, c.contact_name,
                   cs.operator_id, u.display_name as operator_name,
                   (select r.id from recordings r
                     where r.call_session_id = cs.id and r.status = 'stored'
                     order by r.created_at desc limit 1) as recording_id
              from call_sessions cs
              left join customers c on c.id = cs.customer_id
              left join users u on u.id = cs.operator_id
              left join disposition_codes dc on dc.code = cs.disposition_code
             where cs.id = ?
            """, id);

        if (rows.isEmpty()) {
            // ★ RLS により他テナントの通話はそもそも 0 行になる。
            //   403 と 404 を区別すると「その id は存在する」が漏れる
            throw new IllegalArgumentException("通話が見つかりません");
        }
        var call = rows.get(0);

        if (user.role() == AuthUser.Role.OPERATOR
            && !user.userId().equals(call.get("operator_id"))) {
            throw new IllegalArgumentException("通話が見つかりません");
        }

        var history = jdbc.queryForList("""
            select cd.code, dc.label, cd.note, cd.source, cd.recorded_at,
                   u.display_name as recorded_by_name
              from call_dispositions cd
              left join disposition_codes dc on dc.code = cd.code
              left join users u on u.id = cd.recorded_by
             where cd.call_session_id = ?
             order by cd.recorded_at
            """, id);

        var events = jdbc.queryForList("""
            select source, dial_state, applied, payload, occurred_at
              from call_events where call_session_id = ?
             order by occurred_at
            """, id);

        return Map.of("call", call, "dispositions", history, "events", events);
    }
}
