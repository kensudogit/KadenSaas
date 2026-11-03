"use client";

/**
 * AI 自動架電。人が話さず、voice が ASR → LLM → TTS で会話する。
 * 発信の関門（DialingGate）と queued → dial の流れはオペレーター架電と同じ。
 */

import { useCallback, useEffect, useState } from "react";
import { ApiError, calls, queue, type QueueItem } from "@/lib/api";

type Phase = "idle" | "reserved" | "dialing" | "live" | "wrapup";

export default function AiOutboundPage() {
  const [campaignId, setCampaignId] = useState("");
  const [item, setItem] = useState<QueueItem | null>(null);
  const [phase, setPhase] = useState<Phase>("idle");
  const [callSessionId, setCallSessionId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [transcript, setTranscript] = useState<string[]>([]);

  const fetchNext = useCallback(async () => {
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
  }, [callSessionId]);

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
    await fetchNext();
  }

  const t = item?.target;

  return (
    <main style={{ maxWidth: 960, margin: "0 auto", padding: 20 }}>
      <h1 style={{ fontSize: 22, marginBottom: 8 }}>AI 自動架電</h1>
      <p style={{ color: "var(--muted)", fontSize: 14, marginBottom: 20 }}>
        生成 AI と音声認識で、AI が発信し相手と会話します。キャンペーンのスクリプトを
        会話の指示として利用します。必要な環境変数: ASR（Deepgram 等）、LLM_API_KEY、
        音声出力用 TTS_API_KEY（OpenAI）。
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
        <div style={{ flex: "0 0 320px" }}>
          <div style={{ fontSize: 13, color: "var(--muted)" }}>キャンペーン ID</div>
          <input
            value={campaignId}
            onChange={(e) => setCampaignId(e.target.value)}
            placeholder="UUID"
          />
        </div>
        <button type="button" onClick={fetchNext} disabled={!campaignId}>
          次の 1 件
        </button>
        <div style={{ marginLeft: "auto", color: "var(--muted)", fontSize: 13 }}>
          状態: {phase}
        </div>
      </header>

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
          <div style={{ fontWeight: 600, marginBottom: 8 }}>
            {t.company_name ?? "（会社名なし）"} / {t.contact_name ?? "—"}
          </div>
          <div style={{ fontSize: 14, color: "var(--muted)" }}>{t.e164}</div>
          {t.note && <p style={{ marginTop: 10, fontSize: 14 }}>{t.note}</p>}

          <div style={{ display: "flex", gap: 10, marginTop: 16 }}>
            <button
              type="button"
              onClick={startAiCall}
              disabled={phase === "dialing" || phase === "live"}
            >
              AI で発信する
            </button>
            <button type="button" onClick={releaseAndNext}>
              スキップして次へ
            </button>
          </div>
        </section>
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
