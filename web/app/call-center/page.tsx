"use client";

/**
 * 着信オペレーター支援。
 *
 * ★ 架電画面とは別物。ここから発信しない。
 * ★ 読み込み中・エラー・0 件を分ける。RLS が黙って空を返す不具合を
 *   「ナレッジが無い」と誤認しないため。
 * ★ 実音声認識精度も RAG 精度も測っていない。音声は学習に使わない。
 */

import { CSSProperties, FormEvent, useEffect, useState } from "react";
import {
  callCenter,
  getToken,
  type AssistResult,
  type InquiryRow,
  type KnowledgeArticle,
} from "@/lib/api";

type KnowledgeState =
  | { phase: "loading" }
  | { phase: "error"; message: string }
  | { phase: "ready"; rows: KnowledgeArticle[] };

export default function CallCenterPage() {
  const [text, setText] = useState("請求書が届きません。再発行できますか。");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<AssistResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [history, setHistory] = useState<InquiryRow[] | null>(null);
  const [knowledge, setKnowledge] = useState<KnowledgeState>({ phase: "loading" });
  const [seeding, setSeeding] = useState(false);
  const [seedMsg, setSeedMsg] = useState<string | null>(null);
  const [openAiOn, setOpenAiOn] = useState(false);

  async function loadSide() {
    try {
      const [inq, arts] = await Promise.all([
        callCenter.inquiries(),
        callCenter.knowledge(),
      ]);
      setHistory(inq);
      setKnowledge({ phase: "ready", rows: arts });
    } catch (e) {
      setKnowledge({
        phase: "error",
        message: e instanceof Error ? e.message : "取得に失敗しました",
      });
    }
  }

  useEffect(() => {
    if (!getToken()) {
      window.location.href = "/";
      return;
    }
    loadSide();
    callCenter
      .status()
      .then((s) => setOpenAiOn(s.openaiConfigured))
      .catch(() => undefined);
  }, []);

  async function seedCallCenterDemo() {
    setSeeding(true);
    setSeedMsg(null);
    try {
      const r = await callCenter.seedDemo();
      setSeedMsg(
        r.knowledgeArticlesAdded || r.demoInquiriesAdded
          ? `FAQ +${r.knowledgeArticlesAdded} 件、受付履歴 +${r.demoInquiriesAdded} 件を入れました`
          : "すでにデモ FAQ があります",
      );
      await loadSide();
    } catch (err) {
      setSeedMsg(err instanceof Error ? err.message : "サンプルを入れられませんでした");
    } finally {
      setSeeding(false);
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const data = await callCenter.assist(text);
      setResult(data);
      await loadSide();
    } catch (err) {
      setError(err instanceof Error ? err.message : "回答案を作れませんでした");
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    await callCenter.deleteInquiry(id);
    await loadSide();
  }

  return (
    <main style={{ maxWidth: 1000, margin: "0 auto", padding: "0 20px 40px" }}>
      <h1 style={{ fontSize: 20, margin: "0 0 4px" }}>コールセンター</h1>
      <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
        着信の文言から意図・ナレッジ出典・回答案を出します。ここから発信はしません。
        {openAiOn
          ? " OPENAI_API_KEY 設定時は、ヒットした FAQ だけを根拠に OpenAI が回答案を作成します。"
          : " OpenAI 未設定時はテンプレート回答です。"}
        精度は未計測です。音声は学習に使いません。
      </p>

      <form onSubmit={submit} style={{ marginBottom: 24 }}>
        <label htmlFor="inquiry-text" style={{ display: "block", fontWeight: 600, marginBottom: 6 }}>
          問い合わせ本文
        </label>
        <textarea
          id="inquiry-text"
          aria-label="問い合わせ本文"
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={4}
          style={{ width: "100%", maxWidth: 720 }}
        />
        <p>
          <button
            type="submit"
            disabled={!text.trim() || busy}
            style={{
              background: "var(--accent)",
              color: "#fff",
              borderColor: "transparent",
            }}
          >
            {busy ? "処理中…" : "回答案を作る"}
          </button>
        </p>
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

      <section style={{ marginBottom: 28 }}>
        <h2 style={{ fontSize: 16 }}>意図</h2>
        <pre style={box}>{result ? `${result.intent}（${result.nlp.note}）` : "まだありません"}</pre>
        <h2 style={{ fontSize: 16 }}>出典</h2>
        <pre style={box}>
          {result
            ? result.hits.length
              ? result.hits.map((h) => `#${h.chunkId} ${h.content}`).join("\n")
              : "該当なし"
            : "まだありません"}
        </pre>
        <h2 style={{ fontSize: 16 }}>回答案</h2>
        <pre style={{ ...box, whiteSpace: "pre-wrap" }}>
          {result?.reply ?? "まだありません"}
        </pre>
        {result?.note && (
          <p style={{ color: "var(--muted)", fontSize: 12, marginTop: 6 }}>{result.note}</p>
        )}
      </section>

      <section style={{ marginBottom: 28 }}>
        <h2 style={{ fontSize: 16 }}>ナレッジ</h2>
        {knowledge.phase === "loading" && (
          <p style={{ color: "var(--muted)" }}>読み込んでいます…</p>
        )}
        {knowledge.phase === "error" && (
          <div role="alert" style={{ color: "var(--danger)" }}>
            {knowledge.message}
          </div>
        )}
        {knowledge.phase === "ready" && knowledge.rows.length === 0 && (
          <div style={{ color: "var(--muted)" }}>
            <p style={{ marginTop: 0 }}>
              このテナントに FAQ がありません。「回答案を作る」を押すとデモ FAQ が自動で入ります。
              先に履歴まで見たい場合は下のボタン（FAQ 5 件＋受付履歴 3 件）を使ってください。
            </p>
            <p>
              <button type="button" disabled={seeding} onClick={seedCallCenterDemo}>
                {seeding ? "投入中…" : "コールセンター用サンプルを投入"}
              </button>
            </p>
            {seedMsg && <p style={{ color: "var(--fg)" }}>{seedMsg}</p>}
          </div>
        )}
        {knowledge.phase === "ready" && knowledge.rows.length > 0 && (
          <ul style={{ paddingLeft: 18 }}>
            {knowledge.rows.map((row) => (
              <li key={row.id}>
                <b>{row.title}</b>
                <span style={{ color: "var(--muted)", fontSize: 13 }}> / {row.intent}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h2 style={{ fontSize: 16 }}>受付履歴</h2>
        {history === null && <p style={{ color: "var(--muted)" }}>読み込んでいます…</p>}
        {history && history.length === 0 && (
          <div style={{ color: "var(--muted)" }}>
            <p style={{ marginTop: 0 }}>まだありません。</p>
            {knowledge.phase === "ready" && knowledge.rows.length > 0 && (
              <p>
                <button type="button" disabled={seeding} onClick={seedCallCenterDemo}>
                  {seeding ? "投入中…" : "デモ用の受付履歴を入れる"}
                </button>
              </p>
            )}
          </div>
        )}
        {history && history.length > 0 && (
          <ul style={{ paddingLeft: 0, listStyle: "none" }}>
            {history.map((row) => (
              <li
                key={row.id}
                style={{
                  border: "1px solid var(--line)",
                  borderRadius: 8,
                  padding: 12,
                  marginBottom: 10,
                  background: "var(--panel)",
                }}
              >
                <div>{row.reply}</div>
                <div style={{ color: "var(--muted)", fontSize: 12, margin: "6px 0" }}>
                  {row.intent} / {row.createdAt}
                </div>
                <button type="button" onClick={() => remove(row.id)}>
                  削除
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}

const box: CSSProperties = {
  background: "var(--panel)",
  padding: 10,
  borderRadius: 8,
  border: "1px solid var(--line)",
};
