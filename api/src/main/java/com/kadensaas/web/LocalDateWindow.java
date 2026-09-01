package com.kadensaas.web;

import java.time.LocalDate;

/**
 * 集計の対象期間。テナントのローカル日付で受け取り、
 * SQL には started_at の絶対時刻の範囲として渡す。
 *
 * <p>★ なぜ「日付のまま where に置かない」のか。
 * 素直に書くと {@code where local_date between ? and ?} になるが、
 * {@code local_date} は {@code (started_at at time zone t.timezone)::date} という
 * 式で、索引が無い。しかも timezone は結合先の tenants にあるので、
 * プランナは「全件読んでから式を評価する」以外を選べない。
 * 通話 40 万件で実測して 300〜480ms、すべて全走査だった。
 *
 * <p>★ 多テナントではここが効く。全走査は他テナントの行も読んでから
 * RLS で捨てるので、1 社のダッシュボードの重さが基盤全体の通話量で決まる。
 * 自社の通話が少なくても遅くなり、しかも原因が自分の側に無い。
 *
 * <p>★ ローカル日付の範囲は、絶対時刻の範囲に一対一で直せる。
 * 直せば {@code call_sessions_tenant_started_idx (tenant_id, started_at desc)} が
 * そのまま効く。境界の作り方は {@code app_tenant_day_start()}（V14）に
 * 閉じ込めてある。各クエリに書き散らすと、1 箇所間違えたときに
 * 「集計が 1 日ずれる」という気付きにくい形で壊れる。
 *
 * <p>★ 終端は開区間（{@code < 翌日の 0 時}）。{@code <=} にすると
 * 最終日の 0 時ちょうどの通話しか入らない。
 */
record LocalDateWindow(LocalDate from, LocalDate toExclusive) {

    /** 既定は直近 30 日。全期間を既定にすると、行が増えるほど画面が重くなる。 */
    private static final int DEFAULT_DAYS = 30;

    static LocalDateWindow of(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        // ★ 逆順で渡されたら入れ替える。0 件を返して「データが無い」と
        //   誤解させるより、意図どおりの期間を返すほうがよい
        if (start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        return new LocalDateWindow(start, end.plusDays(1));
    }

    /**
     * where 句に貼る条件。
     *
     * @param column started_at 相当の列（{@code cs.started_at} / {@code f.started_at}）
     */
    static String sql(String column) {
        return " " + column + " >= app_tenant_day_start(?) and "
             + column + " <  app_tenant_day_start(?) ";
    }
}
