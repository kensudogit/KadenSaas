package com.kadensaas.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 分析。時間帯・曜日・担当者・止めた理由の 4 軸。
 *
 * <p>★ 「接続とは何か」をここで定義しない。すべて {@code kpi_call_facts} の
 * 列（{@code counts_in_denominator} / {@code is_connected} / {@code is_success}）を
 * そのまま使う。ここで独自の条件を書くと、ダッシュボードと分析画面で
 * 「接続率」が別の数字になり、どちらが正しいのか誰も言えなくなる。
 * 集計の形（何で group by するか）だけがこのクラスの担当。
 *
 * <p>★ 率を返さない。分子と分母を返す。丸め方を画面側に散らさないため。
 * ダッシュボードと同じ約束。
 *
 * <p>★ 期間で絞れるようにする。全期間の平均だけだと、施策の前後で
 * 何が変わったかが見えない。既定は直近 30 日。
 *
 * <p>★ 絞り込みは {@code local_date}（式）ではなく {@code started_at}（索引付き）で行う。
 * 理由は {@link LocalDateWindow} に書いてある。ここを式に戻すと、
 * すべての集計が call_sessions の全走査に落ちる。
 *
 * <p>★ manager 以上に限定する。担当者別の成績は評価に直結するので、
 * オペレーターが互いの数字を見られる状態を既定にはしない。
 */
@RestController
@RequestMapping("/api/v1/analytics")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
// ★ 必須。JdbcTemplate を Spring のトランザクションの外で使うと
//   set_config('app.tenant_id') が走らず、RLS が例外を出さずに 0 行を返す。
//   「分析が全部 0」という形でしか気付けない
@Transactional(readOnly = true)
public class AnalyticsController {

    private final JdbcTemplate jdbc;

    public AnalyticsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 時間帯別。
     *
     * <p>★ 架電業務で最も効く分析。どの時間に鳴らすと繋がるかが分かると、
     * 同じ人員でも成果が変わる。
     */
    @GetMapping("/hourly")
    public List<Map<String, Object>> hourly(@RequestParam(required = false) LocalDate from,
                                            @RequestParam(required = false) LocalDate to) {
        var w = LocalDateWindow.of(from, to);
        return jdbc.queryForList("""
            select local_hour,
                   count(*) filter (where counts_in_denominator)                  as denominator,
                   count(*) filter (where counts_in_denominator and is_connected) as connected,
                   count(*) filter (where counts_in_denominator and is_success)   as successes
              from kpi_call_facts
             where
            """ + LocalDateWindow.sql("started_at") + """
             group by local_hour
             order by local_hour
            """, w.from(), w.toExclusive());
    }

    /**
     * 曜日別。
     *
     * <p>★ 時間帯と分けて見る。「金曜の夕方だけ繋がらない」のような偏りは、
     * 時間帯だけを見ていると平均に埋もれる。
     */
    @GetMapping("/weekday")
    public List<Map<String, Object>> weekday(@RequestParam(required = false) LocalDate from,
                                             @RequestParam(required = false) LocalDate to) {
        var w = LocalDateWindow.of(from, to);
        return jdbc.queryForList("""
            select local_weekday,
                   count(*) filter (where counts_in_denominator)                  as denominator,
                   count(*) filter (where counts_in_denominator and is_connected) as connected,
                   count(*) filter (where counts_in_denominator and is_success)   as successes
              from kpi_call_facts
             where
            """ + LocalDateWindow.sql("started_at") + """
             group by local_weekday
             order by local_weekday
            """, w.from(), w.toExclusive());
    }

    /**
     * 担当者別。
     *
     * <p>★ 件数を必ず併記する。率だけを並べると、10 件で 3 件成功した人が
     * 500 件で 120 件成功した人より上に来る。人の評価に使われる数字なので、
     * 母数が見えない形で出さない。
     *
     * <p>★ 退職などで users から消えた担当者の通話も残る。
     * 名前を内部結合にすると、その分が黙って集計から落ちる。
     *
     * <p>★ 名前の結合は集計の**後**に行う。先に結合すると、
     * 通話 1 行ごとに users を引くことになる。担当者は数十人しかいないのに、
     * 通話の件数ぶん結合するのは無駄で、しかも件数に比例して伸びる。
     */
    @GetMapping("/operator")
    public List<Map<String, Object>> operator(@RequestParam(required = false) LocalDate from,
                                              @RequestParam(required = false) LocalDate to) {
        var w = LocalDateWindow.of(from, to);
        return jdbc.queryForList("""
            with agg as (
              select f.operator_id,
                     count(*) filter (where f.counts_in_denominator)  as denominator,
                     count(*) filter (where f.counts_in_denominator
                                        and f.is_connected)           as connected,
                     count(*) filter (where f.counts_in_denominator
                                        and f.is_conversation)        as conversations,
                     count(*) filter (where f.counts_in_denominator
                                        and f.is_success)             as successes,
                     count(*) filter (where f.was_blocked)            as blocked,
                     coalesce(avg(f.duration_seconds)
                       filter (where f.is_connected and f.duration_seconds > 0), 0)::int
                       as avg_talk_seconds
                from kpi_call_facts f
               where
            """ + LocalDateWindow.sql("f.started_at") + """
               group by f.operator_id
            )
            select agg.operator_id,
                   coalesce(u.display_name, '（担当者なし）') as operator_name,
                   u.status                                   as operator_status,
                   agg.denominator, agg.connected, agg.conversations,
                   agg.successes, agg.blocked, agg.avg_talk_seconds
              from agg
              left join users u on u.id = agg.operator_id
             order by agg.successes desc, agg.denominator desc
            """, w.from(), w.toExclusive());
    }

    /**
     * 止めた理由（閉門理由）の内訳。
     *
     * <p>★ これを画面に出しておくのが重要。関門が想定より多く止めていると、
     * 「架電数が伸びない」の原因がリスト側（DNC 過多・時間帯外・上限）に
     * あることに気付ける。出していないと、担当者の頑張り不足として扱われる。
     */
    @GetMapping("/blocked")
    public List<Map<String, Object>> blocked(@RequestParam(required = false) LocalDate from,
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
