package com.kadensaas.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * KPI。
 *
 * <p>★ 率（%）を返さない。分子と分母をそのまま返す。
 * 率だけを返すと、画面ごとに丸め方や母数の解釈が変わり、
 * 「同じ指標なのに数字が違う」がまた起きる。
 * 「32.4%」ではなく「162 / 500」を渡し、表示は受け取った側が組み立てる。
 *
 * <p>★ 集計の定義は kpi_call_facts ビューが唯一の出所。
 * ここに新しい集計 SQL を書き足さない。書き足すと、
 * Spring Boot 側と FastAPI 側で「接続率」が別物になる。
 */
@RestController
@RequestMapping("/api/v1/kpi")
// ★ 必須。JdbcTemplate を Spring のトランザクションの外で使うと、
//   set_config('app.tenant_id') が走らず RLS が 0 行を返す。
//   例外は出ないので「集計が全部 0」という形でしか気付けない。
//   実際にこれで KPI の内訳が空になった。
@Transactional(readOnly = true)
public class KpiController {

    private final JdbcTemplate jdbc;

    public KpiController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    public List<Map<String, Object>> summary(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        LocalDate start = from != null ? from : LocalDate.now().minusDays(29);
        LocalDate end = to != null ? to : LocalDate.now();

        return jdbc.queryForList("""
            select local_date, campaign_id, operator_id,
                   attempts_total, denominator, connected, conversations,
                   successes, blocked, talk_seconds, avg_talk_seconds
              from kpi_daily_summary
             where local_date between ? and ?
             order by local_date desc
            """, start, end);
    }

    /**
     * 時間帯別の接続。
     *
     * <p>★ 架電業務で最も効く分析。どの時間に鳴らすと繋がるかが分かると、
     * 同じ人員でも成果が変わる。
     */
    @GetMapping("/hourly")
    public List<Map<String, Object>> hourly() {
        return jdbc.queryForList(
            "select local_hour, denominator, connected from kpi_hourly_connect "
            + "order by local_hour");
    }

    /**
     * 止めた発信の内訳。
     *
     * <p>★ これを画面に出しておくのが重要。関門が想定より多く止めていると、
     * 「架電数が伸びない」の原因がリスト側にあることに気付ける。
     */
    @GetMapping("/blocked")
    public List<Map<String, Object>> blocked() {
        return jdbc.queryForList("""
            select blocked_reason, count(*) as count
              from kpi_call_facts
             where was_blocked
             group by blocked_reason
             order by count desc
            """);
    }
}
