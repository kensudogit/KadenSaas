"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError, calls, ivrOutbound, queue, type AiOutboundTargetRow } from "@/lib/api";

export default function IvrOutboundPage() {
  const [overview, setOverview] = useState<Awaited<ReturnType<typeof ivrOutbound.overview>> | null>(
    null,
  );
  const [campaignId, setCampaignId] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      let o = await ivrOutbound.overview();
      if (!o.campaignId) {
        const seeded = await ivrOutbound.seedDemo();
        setNotice(seeded.message);
        o = seeded.overview;
      }
      setOverview(o);
      if (o.campaignId) setCampaignId(o.campaignId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "取得に失敗しました");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function dialOne(row: AiOutboundTargetRow) {
    if (!campaignId) return;
    setError(null);
    try {
      const reserved = await queue.next(campaignId);
      if (!reserved.available || !reserved.target) {
        setNotice("キューから取得できませんでした");
        return;
      }
      const result = await calls.dial(
        reserved.target.phone_id,
        campaignId,
        reserved.target.target_id,
        false,
        "ivr",
      );
      if (!result.accepted) {
        setNotice(result.message ?? "発信できません");
        await queue.release(reserved.target.target_id);
        return;
      }
      await calls.start(result.callSessionId!);
      setNotice(`${row.companyName} へ IVR 架電を開始しました`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 503) {
        setNotice("電話機能が無効です");
      } else {
        setError(e instanceof Error ? e.message : "発信に失敗しました");
      }
    }
  }

  const targets = overview?.targets ?? [];

  return (
    <main style={{ maxWidth: 960, margin: "0 auto", padding: 20 }}>
      <h1 style={{ fontSize: 22, marginBottom: 8 }}>IVR 自動架電</h1>
      <p style={{ color: "var(--muted)", fontSize: 14, marginBottom: 16 }}>
        応答後に Twilio Gather で 1・2・9 のメニューを流します。サンプル番号 03-1234-54xx。
      </p>

      {notice && <p style={{ color: "var(--warn)", fontSize: 14 }}>{notice}</p>}
      {error && (
        <p role="alert" style={{ color: "var(--danger)", fontSize: 14 }}>
          {error}
        </p>
      )}

      {overview?.script && (
        <pre
          style={{
            whiteSpace: "pre-wrap",
            fontSize: 13,
            padding: 12,
            border: "1px solid var(--border)",
            borderRadius: 8,
          }}
        >
          {overview.script}
        </pre>
      )}

      <table style={{ width: "100%", marginTop: 16, fontSize: 14, borderCollapse: "collapse" }}>
        <thead>
          <tr style={{ borderBottom: "1px solid var(--border)", textAlign: "left" }}>
            <th style={{ padding: 8 }}>会社</th>
            <th style={{ padding: 8 }}>電話</th>
            <th style={{ padding: 8 }} />
          </tr>
        </thead>
        <tbody>
          {targets.map((t) => (
            <tr key={t.targetId} style={{ borderBottom: "1px solid var(--border)" }}>
              <td style={{ padding: 8 }}>{t.companyName}</td>
              <td style={{ padding: 8 }}>{t.e164}</td>
              <td style={{ padding: 8 }}>
                <button type="button" onClick={() => void dialOne(t)}>
                  IVR 発信
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  );
}
