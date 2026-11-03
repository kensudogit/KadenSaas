"use client";

/**
 * AI 自動架電。人が話さず、voice が ASR → LLM → TTS で会話する。
 * サンプルキャンペーンは /api/v1/ai-outbound/seed-demo で登録（03-1234-52xx）。
 */

import { useCallback, useEffect, useState } from "react";
import {
  aiOutbound,
  ApiError,
  calls,
  queue,
  type AiOutboundOverview,
  type AiOutboundTargetRow,
  type QueueItem,
} from "@/lib/api";

type Phase = "idle" | "reserved" | "dialing" | "live" | "wrapup";

export default function AiOutboundPage() {
  const [overview, setOverview] = useState<AiOutboundOverview | null>(null);
  const [loadingOverview, setLoadingOverview] = useState(true);
  const [seeding, setSeeding] = useState(false);
  const [campaignId, setCampaignId] = useState("");
  const [item, setItem] = useState<QueueItem | null>(null);
  const [phase, setPhase] = useState<Phase>("idle");
  const [callSessionId, setCallSessionId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [transcript, setTranscript] = useState<string[]>([]);

  const loadOverview = useCallback(async () => {
    setLoadingOverview(true);
    try {
      const o = await aiOutbound.overview();
      setOverview(o);
      if (o.campaignId) setCampaignId(o.campaignId);
      return o;
    } catch (e) {
      setError(e instanceof Error ? e.message : "一覧の取得に失敗しました");
      return null;
    } finally {
      setLoadingOverview(false);
    }
  }, []);

  useEffect(() => {
    void (async () => {
      let o = await loadOverview();
      if (o && !o.campaignId) {
        setSeeding(true);
        try {
          const seeded = await aiOutbound.seedDemo();
          setNotice(seeded.message);
          o = seeded.overview;
          setOverview(o);
          if (o.campaignId) setCampaignId(o.campaignId);
        } catch (e) {
          setError(e instanceof Error ? e.message : "サンプル登録に失敗しました");
        } finally {
          setSeeding(false);
        }
      }
    })();
  }, [loadOverview]);

  const fetchNext = useCallback(async () => {
    if (!campaignId) return;
    setNotice(null);
    setError(null);
    setTranscript([]);
    setCallSessionId(null);
    try {
      const next = await queue.next(campaignId);
      setItem(next);
      setPhase(next.available ? "reserved" : "idle");
      if (!next.available) setNotice("キューに AI 架電対象がありません");
    } catch (e) {
      setError(e instanceof Error ? e.message : "取得に失敗しました");
    }
  }, [campaignId]);

  useEffect(() => {
    if (!callSessionId) return;
    let cancelled = false;
    const poll = async () => {
      try {
        const detail = await calls.get(callSessionId);
        if (cancelled || !detail) return;
        const state = detail.dialState;
        if (state && ["completed", "failed", "busy", "no_answer", "canceled"].includes(state)) {
          setPhase("wrapup");
          setNotice(`通話が終了しました（${state}）`);
          void loadOverview();
        }
      } catch {
        /* ポーリング失敗は無視 */
      }
    };
    const id = setInterval(poll, 4000);
    poll();
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [callSessionId, loadOverview]);

  async function registerSample() {
    setSeeding(true);
    setError(null);
    try {
      const r = await aiOutbound.seedDemo();
      setOverview(r.overview);
      if (r.overview.campaignId) setCampaignId(r.overview.campaignId);
      setNotice(r.message);
    } catch (e) {
      setError(e instanceof Error ? e.message : "サンプル登録に失敗しました");
    } finally {
      setSeeding(false);
    }
  }

  async function startAiCall() {
    if (!item?.target) return;
    setError(null);
    setNotice(null);
    setPhase("dialing");
    try {
      const result = await calls.dial(
        item.target.phone_id,
        campaignId || undefined,
        item.target.target_id,
        true,
      );

      if (!result.accepted) {
        setNotice(result.message ?? result.blockedReason ?? "発信できません");
        setPhase("reserved");
        return;
      }

      setCallSessionId(result.callSessionId!);
      await calls.start(result.callSessionId!);
      setPhase("live");
      setNotice(
        "AI が通話中です。相手の発言は voice で文字起こしされ、LLM が応答を生成します。",
      );
      setTranscript((t) => [...t, "（通話開始）"]);
    } catch (e) {
      if (e instanceof ApiError && e.status === 503) {
        setNotice("電話機能が無効です（Twilio の設定が未完了）");
      } else {
        setError(e instanceof Error ? e.message : "AI 架電の開始に失敗しました");
      }
      setPhase("reserved");
    }
  }

  async function releaseAndNext() {
    if (item?.target) await queue.release(item.target.target_id);
    setPhase("idle");
    await loadOverview();
    await fetchNext();
  }

  const t = item?.target;
  const targets = overview?.targets ?? [];

  return (
    <main style={{ maxWidth: 960, margin: "0 auto", padding: 20 }}>
      <h1 style={{ fontSize: 22, marginBottom: 8 }}>AI 自動架電</h1>
      <p style={{ color: "var(--muted)", fontSize: 14, marginBottom: 20 }}>
        生成 AI と音声認識で、AI が発信し相手と会話します。キャンペーンのスクリプトを
        会話の指示として利用します。サンプル番号は 03-1234-52xx（架空）です。
      </p>

      <header
        style={{
          display: "flex",
          gap: 12,
          alignItems: "flex-end",
          marginBottom: 16,
          flexWrap: "wrap",
        }}
      >
        <div style={{ flex: "1 1 280px" }}>
          <div style={{ fontSize: 13, color: "var(--muted)" }}>キャンペーン</div>
          <div style={{ fontWeight: 600, fontSize: 15 }}>
            {overview?.campaignName ?? "（未登録）"}
          </div>
          {campaignId && (
            <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 4 }}>
              ID: {campaignId}
            </div>
          )}
        </div>
        <button type="button" onClick={registerSample} disabled={seeding}>
          {seeding ? "登録中…" : "サンプルを再登録"}
        </button>
        <button type="button" onClick={fetchNext} disabled={!campaignId || seeding}>
          次の 1 件を取る
        </button>
        <div style={{ marginLeft: "auto", color: "var(--muted)", fontSize: 13 }}>
          状態: {phase}
          {overview != null && ` / 待ち ${overview.pendingCount} 件`}
        </div>
      </header>

      {loadingOverview && (
        <p style={{ color: "var(--muted)", fontSize: 14 }}>サンプルデータを読み込んでいます…</p>
      )}

      {overview?.script && (
        <section
          style={{
            border: "1px solid var(--border)",
            borderRadius: 8,
            padding: 14,
            marginBottom: 16,
            fontSize: 14,
            whiteSpace: "pre-wrap",
          }}
        >
          <div style={{ fontSize: 13, color: "var(--muted)", marginBottom: 8 }}>
            AI 会話スクリプト（指示）
          </div>
          {overview.script}
        </section>
      )}

      {targets.length > 0 && (
        <section style={{ marginBottom: 20 }}>
          <h2 style={{ fontSize: 16, marginBottom: 10 }}>架電先サンプル一覧</h2>
          <div style={{ overflowX: "auto" }}>
            <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 14 }}>
              <thead>
                <tr style={{ textAlign: "left", borderBottom: "1px solid var(--border)" }}>
                  <th style={{ padding: "8px 6px" }}>会社</th>
                  <th style={{ padding: "8px 6px" }}>担当</th>
                  <th style={{ padding: "8px 6px" }}>電話</th>
                  <th style={{ padding: "8px 6px" }}>状態</th>
                  <th style={{ padding: "8px 6px" }}>備考</th>
                </tr>
              </thead>
              <tbody>
                {targets.map((row: AiOutboundTargetRow) => (
                  <tr
                    key={row.targetId}
                    style={{
                      borderBottom: "1px solid var(--border)",
                      opacity: row.doNotCall ? 0.65 : 1,
                    }}
                  >
                    <td style={{ padding: "8px 6px" }}>{row.companyName}</td>
                    <td style={{ padding: "8px 6px" }}>{row.contactName ?? "—"}</td>
                    <td style={{ padding: "8px 6px" }}>{row.e164}</td>
                    <td style={{ padding: "8px 6px" }}>
                      {row.doNotCall ? "DNC" : row.state}
                    </td>
                    <td style={{ padding: "8px 6px", color: "var(--muted)" }}>
                      {row.doNotCall
                        ? "関門で止まる例"
                        : row.note ?? "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {notice && (
        <div
          style={{
            border: "1px solid var(--warn)",
            color: "var(--warn)",
            borderRadius: 6,
            padding: "10px 12px",
            marginBottom: 14,
            fontSize: 14,
          }}
        >
          {notice}
        </div>
      )}

      {error && (
        <div
          role="alert"
          style={{
            border: "1px solid var(--danger)",
            color: "var(--danger)",
            borderRadius: 6,
            padding: "10px 12px",
            marginBottom: 14,
            fontSize: 14,
          }}
        >
          {error}
        </div>
      )}

      {t && (
        <section
          style={{
            border: "1px solid var(--border)",
            borderRadius: 8,
            padding: 16,
            marginBottom: 16,
          }}
        >
          <div style={{ fontSize: 13, color: "var(--muted)", marginBottom: 6 }}>
            いま予約中の 1 件
          </div>
          <div style={{ fontWeight: 600, marginBottom: 8 }}>
            {t.company_name ?? "（会社名なし）"} / {t.contact_name ?? "—"}
          </div>
          <div style={{ fontSize: 14, color: "var(--muted)" }}>{t.e164}</div>
          {t.is_dnc && (
            <p style={{ marginTop: 8, color: "var(--warn)", fontSize: 14 }}>
              再勧誘拒否のため発信できません（デモ用）
            </p>
          )}
          {t.note && <p style={{ marginTop: 10, fontSize: 14 }}>{t.note}</p>}

          <div style={{ display: "flex", gap: 10, marginTop: 16 }}>
            <button
              type="button"
              onClick={startAiCall}
              disabled={phase === "dialing" || phase === "live" || t.is_dnc}
            >
              AI で発信する
            </button>
            <button type="button" onClick={releaseAndNext}>
              スキップして次へ
            </button>
          </div>
        </section>
      )}

      {!t && campaignId && !loadingOverview && phase === "idle" && (
        <p style={{ color: "var(--muted)", fontSize: 14 }}>
          「次の 1 件を取る」でキューから架電先を予約してください。
        </p>
      )}

      {callSessionId && (
        <section style={{ fontSize: 13, color: "var(--muted)" }}>
          通話 ID: {callSessionId}
        </section>
      )}

      {transcript.length > 0 && (
        <section style={{ marginTop: 20 }}>
          <h2 style={{ fontSize: 16 }}>ログ</h2>
          <ul>
            {transcript.map((line, i) => (
              <li key={i}>{line}</li>
            ))}
          </ul>
        </section>
      )}
    </main>
  );
}
