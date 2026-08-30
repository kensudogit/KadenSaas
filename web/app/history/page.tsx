"use client";

/**
 * 架電履歴。発信日時・通話時間・結果・録音。
 *
 * ★ 止めた発信（blocked）も並べる。「かけたが繋がらなかった」と
 *   「そもそもかけていない」は別物で、後者が見えないと
 *   「なぜ架電数が伸びないのか」が分からない。理由まで出す。
 *
 * ★ オペレーターには自分の通話しか返らない（サーバー側で絞っている）。
 *   その事実を画面に明示する。黙って絞ると「件数が合わない」になる。
 *
 * ★ 録音は押したときに初めて URL を取りに行く。一覧の描画時にまとめて
 *   取ると、聞いていない録音まで参照記録が残り、記録が意味を失う。
 */

import { useCallback, useEffect, useState } from "react";
import { getToken, history, recordings, type HistoryRow } from "@/lib/api";

const PAGE_SIZE = 50;

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

const DIAL_STATE_LABELS: Record<string, string> = {
  queued: "発信待ち",
  dialing: "発信中",
  ringing: "呼び出し中",
  in_progress: "通話中",
  completed: "終了",
  failed: "失敗",
  busy: "話中",
  no_answer: "応答なし",
  canceled: "取消",
  blocked: "発信せず",
};

const KINDS = [
  { value: "all", label: "すべて" },
  { value: "dialed", label: "実際にかけた分" },
  { value: "blocked", label: "止めた分" },
  { value: "recorded", label: "録音あり" },
];

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function daysAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

function duration(seconds: number | null): string {
  if (seconds === null || seconds === undefined) return "—";
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return m > 0 ? `${m}分${String(s).padStart(2, "0")}秒` : `${s}秒`;
}

function datetime(iso: string): string {
  const d = new Date(iso);
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, "0")}:${String(
    d.getMinutes(),
  ).padStart(2, "0")}`;
}

export default function HistoryPage() {
  const [rows, setRows] = useState<HistoryRow[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [scopedToSelf, setScopedToSelf] = useState(false);

  const [from, setFrom] = useState(daysAgo(29));
  const [to, setTo] = useState(today());
  const [kind, setKind] = useState("all");
  const [q, setQ] = useState("");

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 再生中の録音。null なら何も鳴っていない
  const [playing, setPlaying] = useState<{ id: string; url: string } | null>(null);
  const [playError, setPlayError] = useState<string | null>(null);

  const load = useCallback(
    async (nextOffset: number) => {
      setLoading(true);
      setError(null);
      try {
        const page = await history.list({
          from,
          to,
          kind,
          q: q || undefined,
          offset: nextOffset,
          limit: PAGE_SIZE,
        });
        setRows(page.rows);
        setTotal(page.total);
        setOffset(page.offset);
        setScopedToSelf(page.scopedToSelf);
      } catch (e) {
        setError(e instanceof Error ? e.message : "取得に失敗しました");
      } finally {
        setLoading(false);
      }
    },
    [from, to, kind, q],
  );

  useEffect(() => {
    if (!getToken()) {
      window.location.href = "/";
      return;
    }
    load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function play(recordingId: string) {
    setPlayError(null);
    setPlaying(null);
    try {
      const r = await recordings.playbackUrl(recordingId);
      setPlaying({ id: recordingId, url: r.url });
    } catch (e) {
      setPlayError(e instanceof Error ? e.message : "録音を取得できませんでした");
    }
  }

  return (
    <main style={{ maxWidth: 1100, margin: "0 auto", padding: "0 20px 40px" }}>
      <h1 style={{ fontSize: 20, margin: "0 0 4px" }}>架電履歴</h1>
      <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
        関門が止めた発信も含めて並べています。
        {scopedToSelf && (
          <strong style={{ color: "var(--fg)" }}>
            {" "}
            表示しているのは自分がかけた通話だけです。
          </strong>
        )}
      </p>

      {/* -------------------------------------------------- 絞り込み */}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          load(0);
        }}
        style={{
          display: "flex",
          gap: 10,
          flexWrap: "wrap",
          alignItems: "flex-end",
          marginBottom: 16,
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
        <label style={{ fontSize: 13 }}>
          <div style={{ color: "var(--muted)" }}>種別</div>
          <select value={kind} onChange={(e) => setKind(e.target.value)}>
            {KINDS.map((k) => (
              <option key={k.value} value={k.value}>
                {k.label}
              </option>
            ))}
          </select>
        </label>
        <label style={{ fontSize: 13, flex: 1, minWidth: 180 }}>
          <div style={{ color: "var(--muted)" }}>相手</div>
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="会社名・電話番号"
          />
        </label>
        <button type="submit" disabled={loading}>
          {loading ? "取得中…" : "絞り込む"}
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
            marginBottom: 12,
          }}
        >
          {error}
        </div>
      )}

      {playError && (
        <div
          role="alert"
          style={{
            border: "1px solid var(--warn)",
            color: "var(--warn)",
            borderRadius: 6,
            padding: "8px 10px",
            marginBottom: 12,
            fontSize: 14,
          }}
        >
          {playError}
        </div>
      )}

      {/* -------------------------------------------------- 再生 */}
      {playing && (
        <div
          style={{
            border: "1px solid var(--line)",
            borderRadius: 8,
            padding: "12px 14px",
            marginBottom: 16,
          }}
        >
          <div style={{ fontSize: 13, marginBottom: 6 }}>録音の再生</div>
          {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
          <audio controls autoPlay src={playing.url} style={{ width: "100%" }} />
          <p style={{ fontSize: 12, color: "var(--muted)", margin: "6px 0 0" }}>
            再生用の URL は 5 分で無効になります。誰がいつ再生したかは記録されます。
          </p>
        </div>
      )}

      {!loading && rows.length === 0 && (
        <div
          style={{
            border: "1px dashed var(--line)",
            borderRadius: 8,
            padding: "24px 20px",
            textAlign: "center",
            color: "var(--muted)",
          }}
        >
          この期間の架電履歴はありません。
        </div>
      )}

      {rows.length > 0 && (
        <>
          <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
            {total} 件中 {offset + 1}–{Math.min(offset + rows.length, total)} 件
          </p>

          <div style={{ overflowX: "auto" }}>
            <table>
              <thead>
                <tr>
                  <th>発信日時</th>
                  <th>相手</th>
                  <th>担当者</th>
                  <th style={{ textAlign: "right" }}>通話時間</th>
                  <th>結果</th>
                  <th>録音</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td style={{ fontVariantNumeric: "tabular-nums", fontSize: 13 }}>
                      {datetime(r.started_at)}
                    </td>
                    <td>
                      {r.company_name ?? "（会社名なし）"}
                      <div
                        style={{
                          fontSize: 12,
                          color: "var(--muted)",
                          fontVariantNumeric: "tabular-nums",
                        }}
                      >
                        {r.to_e164}
                      </div>
                    </td>
                    <td style={{ fontSize: 13 }}>{r.operator_name ?? "—"}</td>
                    <td
                      style={{
                        textAlign: "right",
                        fontVariantNumeric: "tabular-nums",
                        fontSize: 13,
                      }}
                    >
                      {duration(r.duration_seconds)}
                    </td>
                    <td style={{ fontSize: 13 }}>
                      {r.dial_state === "blocked" ? (
                        <span style={{ color: "var(--warn)" }}>
                          発信せず
                          <div style={{ fontSize: 12 }}>
                            {(r.blocked_reason &&
                              BLOCK_REASON_LABELS[r.blocked_reason]) ??
                              r.blocked_reason}
                          </div>
                        </span>
                      ) : (
                        <>
                          <span
                            style={{
                              color: r.disposition_is_success
                                ? "var(--ok)"
                                : "var(--fg)",
                            }}
                          >
                            {r.disposition_label ?? "未入力"}
                          </span>
                          <div style={{ fontSize: 12, color: "var(--muted)" }}>
                            {DIAL_STATE_LABELS[r.dial_state] ?? r.dial_state}
                          </div>
                        </>
                      )}
                    </td>
                    <td>
                      {r.recording_id ? (
                        <button
                          onClick={() => play(r.recording_id as string)}
                          style={{ fontSize: 13, padding: "3px 10px" }}
                        >
                          {playing?.id === r.recording_id ? "再生中" : "再生"}
                        </button>
                      ) : (
                        <span style={{ fontSize: 12, color: "var(--muted)" }}>
                          —
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
            <button
              onClick={() => load(Math.max(0, offset - PAGE_SIZE))}
              disabled={offset === 0 || loading}
            >
              前へ
            </button>
            <button
              onClick={() => load(offset + PAGE_SIZE)}
              disabled={offset + PAGE_SIZE >= total || loading}
            >
              次へ
            </button>
          </div>
        </>
      )}
    </main>
  );
}
