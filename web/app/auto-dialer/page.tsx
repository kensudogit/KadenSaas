"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { autoDialer, type AiOutboundOverview } from "@/lib/api";

export default function AutoDialerPage() {
  const [overview, setOverview] = useState<AiOutboundOverview | null>(null);
  const [campaigns, setCampaigns] = useState<
    Array<{ id: string; name: string; interval_seconds: number; pending_count: number }>
  >([]);
  const [runId, setRunId] = useState<string | null>(null);
  const [intervalSec, setIntervalSec] = useState(30);
  const [mode, setMode] = useState<"operator" | "ai_agent" | "ivr">("ivr");
  const [running, setRunning] = useState(false);
  const [log, setLog] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback(async () => {
    try {
      let camps = await autoDialer.campaigns();
      if (camps.length === 0) {
        const seeded = await autoDialer.seedDemo();
        setOverview(seeded.overview);
        camps = await autoDialer.campaigns();
      }
      setCampaigns(camps);
      if (camps[0]) setIntervalSec(camps[0].interval_seconds);
    } catch (e) {
      setError(e instanceof Error ? e.message : "読み込みに失敗しました");
    }
  }, []);

  useEffect(() => {
    void load();
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [load]);

  const tick = useCallback(async () => {
    if (!runId) return;
    try {
      const r = await autoDialer.tick(runId);
      const line = r.dialed
        ? `発信: ${r.companyName ?? ""} ${r.e164 ?? ""} — ${r.message}`
        : `スキップ (${r.skipReason ?? "—"}): ${r.message}`;
      setLog((prev) => [line, ...prev].slice(0, 30));
      if (r.skipReason === "voice_dial_failed") {
        setError(r.message);
      }
      if (
        r.skipReason === "queue_empty" ||
        r.skipReason === "not_running" ||
        r.skipReason === "voice_dial_failed"
      ) {
        setRunning(false);
        if (timerRef.current) clearInterval(timerRef.current);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "tick に失敗しました");
      setRunning(false);
    }
  }, [runId]);

  useEffect(() => {
    if (!running || !runId) return;
    void tick();
    timerRef.current = setInterval(() => void tick(), intervalSec * 1000);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [running, runId, intervalSec, tick]);

  async function startRun() {
    const camp = campaigns[0];
    if (!camp?.id) {
      setError("キャンペーン ID が取得できません。再読込してください。");
      return;
    }
    setError(null);
    try {
      const run = await autoDialer.start(String(camp.id), mode);
      setRunId(run.id);
      setIntervalSec(run.intervalSeconds);
      setRunning(true);
      setLog([`ラン開始: ${run.campaignName}（${run.conversationMode}）`]);
    } catch (e) {
      setError(e instanceof Error ? e.message : "開始に失敗しました");
    }
  }

  async function stopRun() {
    if (runId) await autoDialer.stop(runId);
    setRunning(false);
    setRunId(null);
    await load();
  }

  return (
    <main style={{ maxWidth: 960, margin: "0 auto", padding: 20 }}>
      <h1 style={{ fontSize: 22, marginBottom: 8 }}>オートダイヤラー</h1>
      <p style={{ color: "var(--muted)", fontSize: 14, marginBottom: 16 }}>
        キャンペーンキューを 1 本ずつ自動発信します（in-flight 中は待機）。
        api から voice へ <code>KADEN_VOICE_BASE_URL</code> 経由で dial します。
        サンプル番号: 03-1234-53xx。
      </p>

      {error && (
        <div role="alert" style={{ color: "var(--danger)", marginBottom: 12 }}>
          {error}
          {error.includes("マイグレーション") || error.includes("データベース") ? (
            <p style={{ fontSize: 13, marginTop: 8, color: "var(--muted)" }}>
              Railway 本番: api に{" "}
              <code>DATABASE_MIGRATOR_URL</code>（kaden_migrator）を設定し、V18 入り
              api を Redeploy。管理者は{" "}
              <code>GET /api/v1/admin/schema/diagnose</code> で確認（詳細{" "}
              <code>db/RAILWAY-FLYWAY.md</code>）。
            </p>
          ) : null}
        </div>
      )}

      <section style={{ marginBottom: 16 }}>
        <label style={{ fontSize: 13, color: "var(--muted)" }}>会話モード</label>
        <select value={mode} onChange={(e) => setMode(e.target.value as typeof mode)}>
          <option value="ivr">IVR（Gather）</option>
          <option value="ai_agent">AI 自動会話</option>
          <option value="operator">Media Stream（人/記録用）</option>
        </select>
      </section>

      {campaigns.map((c) => (
        <div
          key={c.id}
          style={{
            border: "1px solid var(--border)",
            borderRadius: 8,
            padding: 12,
            marginBottom: 10,
          }}
        >
          <div style={{ fontWeight: 600 }}>{c.name}</div>
          <div style={{ fontSize: 13, color: "var(--muted)" }}>
            待ち {c.pending_count} 件 / 間隔 {c.interval_seconds}s
          </div>
        </div>
      ))}

      {overview && overview.targets.length > 0 && (
        <p style={{ fontSize: 13, color: "var(--muted)" }}>
          直近サンプル: {overview.targets.length} 件登録済み
        </p>
      )}

      <div style={{ display: "flex", gap: 10, marginTop: 16 }}>
        <button type="button" onClick={startRun} disabled={running || campaigns.length === 0}>
          自動発信を開始
        </button>
        <button type="button" onClick={stopRun} disabled={!runId}>
          停止
        </button>
        <button type="button" onClick={() => void load()}>
          再読込
        </button>
      </div>

      {log.length > 0 && (
        <ul style={{ marginTop: 20, fontSize: 13, lineHeight: 1.6 }}>
          {log.map((line, i) => (
            <li key={i}>{line}</li>
          ))}
        </ul>
      )}
    </main>
  );
}
