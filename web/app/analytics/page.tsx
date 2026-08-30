"use client";

/**
 * 分析。時間帯・曜日・担当者・止めた理由。
 *
 * ★ 率を単独で出さない。必ず「32.4%（162 / 500）」の形にする。
 *   率だけを並べると、10 件で 3 件成功した人が 500 件で 120 件成功した人より
 *   上に来る。人の評価に使われる画面なので、母数が見えない形で出さない。
 *
 * ★ 期間で絞れる。全期間の平均だけだと、施策の前後で何が変わったかが
 *   見えない。既定は直近 30 日。
 *
 * ★ 「止めた理由」を必ず出す。ここが想定より多いとき、架電数が伸びない
 *   原因はリスト側（DNC 過多・時間帯外・上限）にある。出していないと、
 *   担当者の頑張り不足として扱われてしまう。
 *
 * ★ この画面は manager 以上でないと 403 になる。判定はサーバー側。
 */

import { useCallback, useEffect, useState } from "react";
import {
  analytics,
  getToken,
  type BlockedRow,
  type HourlyRow,
  type OperatorRow,
  type WeekdayRow,
} from "@/lib/api";

const WEEKDAYS = ["", "月", "火", "水", "木", "金", "土", "日"];

const BLOCK_REASON_LABELS: Record<string, string> = {
  do_not_call: "再勧誘拒否",
  outside_hours: "架電可能時間外",
  outside_weekday: "架電対象外の曜日",
  holiday: "祝日",
  max_attempts_per_day: "本日の上限に到達",
  max_attempts_total: "通算の上限に到達",
  already_in_flight: "通話が進行中",
  dialing_disabled: "発信が停止中",
  telephony_not_configured: "発信者番号が未設定",
};

/** ★ 率の表示は必ずこれを通す。分母 0 のときに NaN% を出さない。 */
function ratio(numerator: number, denominator: number): string {
  if (!denominator) return "—";
  return `${((numerator / denominator) * 100).toFixed(1)}%（${numerator} / ${denominator}）`;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function daysAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

export default function AnalyticsPage() {
  const [from, setFrom] = useState(daysAgo(29));
  const [to, setTo] = useState(today());

  const [hourly, setHourly] = useState<HourlyRow[]>([]);
  const [weekday, setWeekday] = useState<WeekdayRow[]>([]);
  const [operators, setOperators] = useState<OperatorRow[]>([]);
  const [blocked, setBlocked] = useState<BlockedRow[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [h, w, o, b] = await Promise.all([
        analytics.hourly(from, to),
        analytics.weekday(from, to),
        analytics.operator(from, to),
        analytics.blocked(from, to),
      ]);
      setHourly(h);
      setWeekday(w);
      setOperators(o);
      setBlocked(b);
    } catch (e) {
      setError(e instanceof Error ? e.message : "取得に失敗しました");
    } finally {
      setLoading(false);
    }
  }, [from, to]);

  useEffect(() => {
    if (!getToken()) {
      window.location.href = "/";
      return;
    }
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const maxHour = Math.max(1, ...hourly.map((h) => h.denominator));
  const maxWeekday = Math.max(1, ...weekday.map((w) => w.denominator));
  const totalBlocked = blocked.reduce((a, b) => a + b.count, 0);

  const empty =
    !loading && hourly.length === 0 && weekday.length === 0 && operators.length === 0;

  return (
    <main style={{ maxWidth: 1100, margin: "0 auto", padding: "0 20px 40px" }}>
      <h1 style={{ fontSize: 20, margin: "0 0 4px" }}>分析</h1>
      <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
        率は必ず「割合（分子 / 分母）」で表示します。母数が見えないと比較できないためです。
      </p>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          load();
        }}
        style={{
          display: "flex",
          gap: 10,
          alignItems: "flex-end",
          flexWrap: "wrap",
          marginBottom: 22,
        }}
      >
        <label style={{ fontSize: 13 }}>
          <div style={{ color: "var(--muted)" }}>開始日</div>
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label style={{ fontSize: 13 }}>
          <div style={{ color: "var(--muted)" }}>終了日</div>
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <button type="submit" disabled={loading}>
          {loading ? "集計中…" : "集計"}
        </button>
      </form>

      {error && (
        <div
          role="alert"
          style={{
            border: "1px solid var(--danger)",
            color: "var(--danger)",
            borderRadius: 6,
            padding: "10px 12px",
            marginBottom: 16,
          }}
        >
          {error}
        </div>
      )}

      {empty && (
        <div
          style={{
            border: "1px dashed var(--line)",
            borderRadius: 8,
            padding: "24px 20px",
            textAlign: "center",
            color: "var(--muted)",
          }}
        >
          この期間に集計できる通話がありません。
        </div>
      )}

      {/* -------------------------------------------------- 時間帯 */}
      {hourly.length > 0 && (
        <section style={{ marginBottom: 30 }}>
          <h2 style={{ fontSize: 16, marginBottom: 2 }}>時間帯</h2>
          <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
            どの時間に鳴らすと繋がるか。同じ人員でも成果が変わる部分です。
          </p>
          <Bars
            rows={hourly.map((h) => ({
              key: String(h.local_hour),
              label: `${String(h.local_hour).padStart(2, "0")}時`,
              denominator: h.denominator,
              connected: h.connected,
            }))}
            max={maxHour}
          />
        </section>
      )}

      {/* -------------------------------------------------- 曜日 */}
      {weekday.length > 0 && (
        <section style={{ marginBottom: 30 }}>
          <h2 style={{ fontSize: 16, marginBottom: 2 }}>曜日</h2>
          <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
            時間帯と分けて見ます。曜日ごとの偏りは、時間帯だけを見ていると平均に埋もれます。
          </p>
          <Bars
            rows={weekday.map((w) => ({
              key: String(w.local_weekday),
              label: `${WEEKDAYS[w.local_weekday] ?? w.local_weekday}曜`,
              denominator: w.denominator,
              connected: w.connected,
            }))}
            max={maxWeekday}
          />
        </section>
      )}

      {/* -------------------------------------------------- 担当者 */}
      {operators.length > 0 && (
        <section style={{ marginBottom: 30 }}>
          <h2 style={{ fontSize: 16, marginBottom: 2 }}>担当者</h2>
          <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
            件数を併記しています。率だけで並べると、母数の小さい人が上に来ます。
          </p>
          <div style={{ overflowX: "auto" }}>
            <table>
              <thead>
                <tr>
                  <th>担当者</th>
                  <th style={{ textAlign: "right" }}>架電</th>
                  <th>接続率</th>
                  <th>会話率</th>
                  <th>成果率</th>
                  <th style={{ textAlign: "right" }}>平均通話</th>
                  <th style={{ textAlign: "right" }}>止めた</th>
                </tr>
              </thead>
              <tbody>
                {operators.map((o) => (
                  <tr key={o.operator_id ?? "none"}>
                    <td>
                      {o.operator_name}
                      {o.operator_status === "disabled" && (
                        <span
                          style={{
                            fontSize: 12,
                            color: "var(--muted)",
                            marginLeft: 6,
                          }}
                        >
                          （無効）
                        </span>
                      )}
                    </td>
                    <td
                      style={{
                        textAlign: "right",
                        fontVariantNumeric: "tabular-nums",
                      }}
                    >
                      {o.denominator}
                    </td>
                    <td style={{ fontSize: 13 }}>
                      {ratio(o.connected, o.denominator)}
                    </td>
                    <td style={{ fontSize: 13 }}>
                      {ratio(o.conversations, o.denominator)}
                    </td>
                    <td style={{ fontSize: 13 }}>
                      {/* ★ 成果率の分母は「会話が成立した通話」。
                          ダッシュボードと同じ定義にそろえる */}
                      {ratio(o.successes, o.conversations)}
                    </td>
                    <td
                      style={{
                        textAlign: "right",
                        fontVariantNumeric: "tabular-nums",
                        fontSize: 13,
                      }}
                    >
                      {o.avg_talk_seconds ? `${o.avg_talk_seconds} 秒` : "—"}
                    </td>
                    <td
                      style={{
                        textAlign: "right",
                        fontVariantNumeric: "tabular-nums",
                        fontSize: 13,
                      }}
                    >
                      {o.blocked}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {/* -------------------------------------------------- 閉門理由 */}
      <section>
        <h2 style={{ fontSize: 16, marginBottom: 2 }}>関門が止めた理由</h2>
        <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
          ここが想定より多いとき、架電数が伸びない原因はリスト側にあります。
        </p>
        {blocked.length ? (
          <table>
            <thead>
              <tr>
                <th>理由</th>
                <th style={{ textAlign: "right" }}>件数</th>
                <th>割合</th>
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
                  <td style={{ fontSize: 13 }}>{ratio(b.count, totalBlocked)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p style={{ color: "var(--muted)", fontSize: 14 }}>
            この期間に止めた発信はありません。
          </p>
        )}
      </section>
    </main>
  );
}

/** 分母を薄い帯、接続を濃い帯で重ねる。件数の差が一目で分かるようにする。 */
function Bars({
  rows,
  max,
}: {
  rows: Array<{ key: string; label: string; denominator: number; connected: number }>;
  max: number;
}) {
  return (
    <div style={{ display: "grid", gap: 4 }}>
      {rows.map((r) => (
        <div key={r.key} style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <div
            style={{
              width: 48,
              fontSize: 13,
              color: "var(--muted)",
              fontVariantNumeric: "tabular-nums",
            }}
          >
            {r.label}
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
                width: `${(r.denominator / max) * 100}%`,
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
                width: `${(r.connected / max) * 100}%`,
                height: "100%",
                background: "var(--accent)",
                borderRadius: 3,
              }}
            />
          </div>
          <div
            style={{ width: 155, fontSize: 13, fontVariantNumeric: "tabular-nums" }}
          >
            {ratio(r.connected, r.denominator)}
          </div>
        </div>
      ))}
    </div>
  );
}
