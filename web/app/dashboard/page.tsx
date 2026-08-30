"use client";

/**
 * KPI ダッシュボード。
 *
 * ★ 率を単独で表示しない。必ず「32.4%（162 / 500）」の形にする。
 *   率だけを出すと、見る人ごとに分母の解釈が変わり、
 *   「同じ指標なのに数字が違う」で毎月揉める。分子と分母が
 *   その場で見えていれば、議論は数分で終わる。
 *
 * ★ サーバーは率を返さない。分子と分母を返し、表示側で組み立てる。
 *   丸め方を 1 箇所に閉じ込めるため。
 *
 * ★ 「関門が止めた件数」を必ず出す。ここが想定より多いとき、
 *   架電数が伸びない原因はリスト側（DNC 過多・時間帯外）にある。
 *   出していないと、担当者の頑張り不足として扱われてしまう。
 */

import { useEffect, useState } from "react";
import { kpi, type KpiRow } from "@/lib/api";

/** ★ 率の表示は必ずこれを通す。分母 0 のときに NaN% を出さない。 */
function ratio(numerator: number, denominator: number): string {
  if (!denominator) return "—";
  const pct = (numerator / denominator) * 100;
  return `${pct.toFixed(1)}%（${numerator} / ${denominator}）`;
}

const BLOCK_REASON_LABELS: Record<string, string> = {
  do_not_call: "再勧誘拒否",
  outside_hours: "架電可能時間外",
  outside_weekday: "架電対象外の曜日",
  holiday: "祝日",
  max_attempts_per_day: "本日の上限に到達",
  max_attempts_total: "通算の上限に到達",
  already_in_flight: "通話が進行中",
  dialing_disabled: "発信が停止中",
};

export default function DashboardPage() {
  const [rows, setRows] = useState<KpiRow[]>([]);
  const [hourly, setHourly] = useState<
    Array<{ local_hour: number; denominator: number; connected: number }>
  >([]);
  const [blocked, setBlocked] = useState<
    Array<{ blocked_reason: string; count: number }>
  >([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([kpi.summary(), kpi.hourly(), kpi.blocked()])
      .then(([s, h, b]) => {
        setRows(s);
        setHourly(h);
        setBlocked(b);
      })
      .catch((e) =>
        setError(e instanceof Error ? e.message : "取得に失敗しました"),
      );
  }, []);

  const total = rows.reduce(
    (acc, r) => ({
      attempts: acc.attempts + r.attempts_total,
      denominator: acc.denominator + r.denominator,
      connected: acc.connected + r.connected,
      conversations: acc.conversations + r.conversations,
      successes: acc.successes + r.successes,
      blocked: acc.blocked + r.blocked,
      talk: acc.talk + r.talk_seconds,
    }),
    {
      attempts: 0,
      denominator: 0,
      connected: 0,
      conversations: 0,
      successes: 0,
      blocked: 0,
      talk: 0,
    },
  );

  const maxHourly = Math.max(1, ...hourly.map((h) => h.denominator));

  return (
    <main style={{ maxWidth: 1100, margin: "0 auto", padding: 20 }}>
      <h1 style={{ fontSize: 20 }}>架電 KPI</h1>

      {error && (
        <div role="alert" style={{ color: "var(--danger)" }}>
          {error}
        </div>
      )}

      <section
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))",
          gap: 12,
          margin: "16px 0 24px",
        }}
      >
        <Metric label="発信数" value={String(total.attempts)} />
        <Metric
          label="接続率"
          value={ratio(total.connected, total.denominator)}
          hint="分母 = 発信数 − 関門で止めた分 − 無効番号"
        />
        <Metric
          label="会話率"
          value={ratio(total.conversations, total.denominator)}
          hint="留守電・受付止まりを除く"
        />
        <Metric
          label="成果率"
          value={ratio(total.successes, total.conversations)}
          hint="分母は会話が成立した通話"
        />
        <Metric
          label="平均通話時間"
          value={
            total.connected
              ? `${Math.round(total.talk / total.connected)} 秒`
              : "—"
          }
        />
        <Metric
          label="関門が止めた件数"
          value={String(total.blocked)}
          hint="多いときは原因がリスト側にある"
        />
      </section>

      {/* -------------------------------------------------- 止めた理由 */}
      <section style={{ marginBottom: 28 }}>
        <h2 style={{ fontSize: 16 }}>関門が止めた理由</h2>
        {blocked.length ? (
          <table>
            <thead>
              <tr>
                <th>理由</th>
                <th style={{ textAlign: "right" }}>件数</th>
              </tr>
            </thead>
            <tbody>
              {blocked.map((b) => (
                <tr key={b.blocked_reason}>
                  <td>
                    {BLOCK_REASON_LABELS[b.blocked_reason] ?? b.blocked_reason}
                  </td>
                  <td
                    style={{
                      textAlign: "right",
                      fontVariantNumeric: "tabular-nums",
                    }}
                  >
                    {b.count}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p style={{ color: "var(--muted)", fontSize: 14 }}>
            止めた発信はありません
          </p>
        )}
      </section>

      {/* -------------------------------------------------- 時間帯 */}
      <section style={{ marginBottom: 28 }}>
        <h2 style={{ fontSize: 16 }}>時間帯別の接続</h2>
        <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
          どの時間に鳴らすと繋がるか。同じ人員でも成果が変わる部分。
        </p>
        <div style={{ display: "grid", gap: 4 }}>
          {hourly.map((h) => (
            <div
              key={h.local_hour}
              style={{ display: "flex", alignItems: "center", gap: 8 }}
            >
              <div
                style={{
                  width: 44,
                  fontSize: 13,
                  color: "var(--muted)",
                  fontVariantNumeric: "tabular-nums",
                }}
              >
                {String(h.local_hour).padStart(2, "0")}時
              </div>
              <div
                style={{
                  flex: 1,
                  height: 16,
                  background: "var(--panel)",
                  borderRadius: 3,
                  position: "relative",
                }}
              >
                <div
                  style={{
                    width: `${(h.denominator / maxHourly) * 100}%`,
                    height: "100%",
                    background: "var(--line)",
                    borderRadius: 3,
                  }}
                />
                <div
                  style={{
                    position: "absolute",
                    top: 0,
                    left: 0,
                    width: `${(h.connected / maxHourly) * 100}%`,
                    height: "100%",
                    background: "var(--accent)",
                    borderRadius: 3,
                  }}
                />
              </div>
              <div
                style={{
                  width: 150,
                  fontSize: 13,
                  fontVariantNumeric: "tabular-nums",
                }}
              >
                {ratio(h.connected, h.denominator)}
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* -------------------------------------------------- 日次 */}
      <section>
        <h2 style={{ fontSize: 16 }}>日次</h2>
        <table>
          <thead>
            <tr>
              <th>日付</th>
              <th style={{ textAlign: "right" }}>発信</th>
              <th style={{ textAlign: "right" }}>分母</th>
              <th style={{ textAlign: "right" }}>接続</th>
              <th>接続率</th>
              <th style={{ textAlign: "right" }}>成果</th>
              <th style={{ textAlign: "right" }}>止めた</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.local_date}>
                <td>{r.local_date}</td>
                <td style={{ textAlign: "right" }}>{r.attempts_total}</td>
                <td style={{ textAlign: "right" }}>{r.denominator}</td>
                <td style={{ textAlign: "right" }}>{r.connected}</td>
                <td>{ratio(r.connected, r.denominator)}</td>
                <td style={{ textAlign: "right" }}>{r.successes}</td>
                <td style={{ textAlign: "right" }}>{r.blocked}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </main>
  );
}

function Metric({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  hint?: string;
}) {
  return (
    <div
      style={{
        border: "1px solid var(--line)",
        borderRadius: 8,
        padding: "12px 14px",
      }}
    >
      <div style={{ fontSize: 13, color: "var(--muted)" }}>{label}</div>
      <div
        style={{ fontSize: 19, fontVariantNumeric: "tabular-nums", marginTop: 2 }}
      >
        {value}
      </div>
      {hint && (
        <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 4 }}>
          {hint}
        </div>
      )}
    </div>
  );
}
