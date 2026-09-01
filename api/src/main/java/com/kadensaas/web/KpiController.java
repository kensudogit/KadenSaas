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
 *
 * <p>★ すべての集計に期間の上限を持たせる。以前 /hourly と /blocked は
 * 全期間を集計していた。定義としては素直だが、行が増えるほど確実に遅くなり、
 * しかも「いつから遅くなったか」が分からない伸び方をする（実測で
 * 通話 40 万件のとき 482ms・一時ファイル 16MB）。運用で見たいのは
 * たいてい直近なので、既定を 30 日にして、必要なら from / to で広げる。
 *
 * <p>★ 絞り込みは {@code local_date}（式）ではなく {@code started_at}（索引付き）で行う。
 * 理由は {@link LocalDateWindow} に書いてある。
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

    /**
     * 日次サマリ。
     *
     * <p>★ kpi_daily_summary ビューを経由しない。ビューは
     * tenant_id / local_date / campaign_id / operator_id で group by しており、
     * 期間で絞るには「全期間を集計してから絞る」か、
     * プランナが述語を押し下げられるかに賭けることになる。
     * 集計の**定義**（分母・分子の意味）は kpi_call_facts が持っているので、
     * そこから直接数えれば定義は 1 つのままで、絞り込みだけが先に効く。
     */
    @GetMapping("/summary")
    public List<Map<String, Object>> summary(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        var w = LocalDateWindow.of(from, to);

        return jdbc.queryForList("""
            select local_date, campaign_id, operator_id,
                   count(*)                                      as attempts_total,
                   count(*) filter (where counts_in_denominator) as denominator,
                   count(*) filter (where counts_in_denominator
                                      and is_connected)          as connected,
                   count(*) filter (where counts_in_denominator
                                      and is_conversation)       as conversations,
                   count(*) filter (where counts_in_denominator
                                      and is_success)            as successes,
                   count(*) filter (where was_blocked)           as blocked,
                   coalesce(sum(duration_seconds)
                     filter (where is_connected), 0)             as talk_seconds,
                   coalesce(avg(duration_seconds)
                     filter (where is_connected and duration_seconds > 0), 0)::int
                                                                 as avg_talk_seconds
              from kpi_call_facts
             where
            """ + LocalDateWindow.sql("started_at") + """
             group by local_date, campaign_id, operator_id
             order by local_date desc
            """, w.from(), w.toExclusive());
    }

    /**
     * 時間帯別の接続。
     *
     * <p>★ 架電業務で最も効く分析。どの時間に鳴らすと繋がるかが分かると、
     * 同じ人員でも成果が変わる。
     *
     * <p>★ 既定は直近 30 日。全期間だと、半年前の悪かった時間帯が
     * いつまでも平均を引っ張り、直したことが数字に出ない。
     */
    @GetMapping("/hourly")
    public List<Map<String, Object>> hourly(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        var w = LocalDateWindow.of(from, to);

        return jdbc.queryForList("""
            select local_hour,
                   count(*) filter (where counts_in_denominator)                  as denominator,
                   count(*) filter (where counts_in_denominator and is_connected) as connected
              from kpi_call_facts
             where
            """ + LocalDateWindow.sql("started_at") + """
             group by local_hour
             order by local_hour
            """, w.from(), w.toExclusive());
    }

    /**
     * 止めた発信の内訳。
     *
     * <p>★ これを画面に出しておくのが重要。関門が想定より多く止めていると、
     * 「架電数が伸びない」の原因がリスト側にあることに気付ける。
     */
    @GetMapping("/blocked")
    public List<Map<String, Object>> blocked(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        var w = LocalDateWindow.of(from, to);

        return jdbc.queryForList("""
            select blocked_reason, count(*) as count
              from kpi_call_facts
             where was_blocked and
            """ + LocalDateWindow.sql("started_at") + """
             group by blocked_reason
             order by count desc
            """, w.from(), w.toExclusive());
    }
}
