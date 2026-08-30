"use client";

/**
 * オペレーター画面。
 *
 * ★ 1 画面で完結させる。顧客情報・過去の履歴・スクリプト・発信操作・結果入力を
 *   同じ画面に置く。3 画面を行き来する作りにすると、1 件あたり数十秒が
 *   毎回消える。月 3000 件なら、それだけで 1 人日以上になる。
 *
 * ★ 結果入力を必須にしすぎない。強制ロックを掛けると、離席や
 *   タブを閉じることで回避され、かえってデータが汚れる。
 *   入れやすくすることで埋めさせる。
 *
 * ★ 関門に止められた場合を「エラー」として赤く出さない。
 *   正しく止めた結果なので、理由を淡々と表示して次へ進ませる。
 *   赤い表示にすると、担当者は「システムの不具合」と受け取って
 *   別経路でかけようとする。
 */

import { useCallback, useEffect, useState } from "react";
import {
  ApiError,
  calls,
  queue,
  type QueueItem,
} from "@/lib/api";

type DispositionCode = {
  code: string;
  label: string;
  isDnc: boolean;
  isConnected: boolean;
};

type Phase = "idle" | "reserved" | "dialing" | "talking" | "wrapup";

export default function OperatorPage() {
  const [campaignId, setCampaignId] = useState("");
  const [item, setItem] = useState<QueueItem | null>(null);
  const [codes, setCodes] = useState<DispositionCode[]>([]);
  const [phase, setPhase] = useState<Phase>("idle");
  const [callSessionId, setCallSessionId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState("");

  useEffect(() => {
    calls.dispositionCodes().then(setCodes).catch(() => {
      /* 一覧が取れなくても画面は開ける */
    });
  }, []);

  const fetchNext = useCallback(async () => {
    setNotice(null);
    setError(null);
    setNote("");
    setCallSessionId(null);
    try {
      const next = await queue.next(campaignId);
      setItem(next);
      setPhase(next.available ? "reserved" : "idle");
      if (!next.available) setNotice("かけられる相手がキューにありません");
    } catch (e) {
      setError(e instanceof Error ? e.message : "取得に失敗しました");
    }
  }, [campaignId]);

  async function dial() {
    if (!item?.target) return;
    setError(null);
    setNotice(null);
    setPhase("dialing");
    try {
      // 1. 関門を通す（api）
      const result = await calls.dial(
        item.target.phone_id,
        campaignId || undefined,
        item.target.target_id,
      );

      if (!result.accepted) {
        // ★ エラーではない。止めた理由をそのまま見せる
        setNotice(result.message ?? "この相手には発信できません");
        setPhase("reserved");
        return;
      }

      setCallSessionId(result.callSessionId!);

      // 2. 実際に鳴らす（voice）
      await calls.start(result.callSessionId!);
      setPhase("talking");
    } catch (e) {
      if (e instanceof ApiError && e.status === 503) {
        setNotice("電話機能が無効です（Twilio の設定が未完了）");
      } else {
        setError(e instanceof Error ? e.message : "発信に失敗しました");
      }
      setPhase("reserved");
    }
  }

  async function record(code: DispositionCode) {
    if (!callSessionId) {
      // 発信せずに結果だけ入れる場合（不在の折り返しなど）は次へ進む
      await skip();
      return;
    }
    try {
      await calls.disposition(callSessionId, code.code, note || undefined);
      setPhase("wrapup");
      await fetchNext();
    } catch (e) {
      setError(e instanceof Error ? e.message : "結果の登録に失敗しました");
    }
  }

  async function skip() {
    if (item?.target) await queue.release(item.target.target_id);
    await fetchNext();
  }

  const t = item?.target;

  return (
    <main style={{ maxWidth: 1100, margin: "0 auto", padding: 20 }}>
      <header
        style={{
          display: "flex",
          gap: 12,
          alignItems: "flex-end",
          marginBottom: 16,
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
        <button onClick={fetchNext} disabled={!campaignId}>
          次の 1 件を受け取る
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

      {!t && (
        <p style={{ color: "var(--muted)" }}>
          キャンペーン ID を入れて「次の 1 件を受け取る」を押してください。
        </p>
      )}

      {t && (
        <div style={{ display: "grid", gridTemplateColumns: "1.2fr 1fr", gap: 16 }}>
          {/* ---------------------------------------------- 左: 相手と操作 */}
          <section
            style={{
              border: "1px solid var(--line)",
              borderRadius: 8,
              padding: 16,
            }}
          >
            <h2 style={{ margin: "0 0 12px", fontSize: 17 }}>
              {t.company_name ?? "(会社名なし)"}
            </h2>
            <table style={{ marginBottom: 14 }}>
              <tbody>
                <tr>
                  <th style={{ width: 110 }}>担当者</th>
                  <td>{t.contact_name ?? "—"}</td>
                </tr>
                <tr>
                  <th>電話</th>
                  <td style={{ fontVariantNumeric: "tabular-nums" }}>
                    {t.raw_number}
                  </td>
                </tr>
                <tr>
                  <th>架電回数</th>
                  <td>{t.attempts} 回</td>
                </tr>
              </tbody>
            </table>

            {/* ★ DNC は発信前に見える位置に置く。押してから止められるより、
                押せないほうが担当者の混乱が少ない */}
            {t.is_dnc && (
              <div
                style={{
                  border: "1px solid var(--danger)",
                  color: "var(--danger)",
                  borderRadius: 6,
                  padding: "8px 10px",
                  marginBottom: 12,
                  fontSize: 14,
                }}
              >
                この番号は再勧誘拒否として登録されています。発信できません。
              </div>
            )}

            {t.note && (
              <p
                style={{
                  background: "var(--panel)",
                  borderRadius: 6,
                  padding: "10px 12px",
                  fontSize: 14,
                }}
              >
                {t.note}
              </p>
            )}

            <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
              <button
                onClick={dial}
                disabled={t.is_dnc || phase === "dialing" || phase === "talking"}
                style={{
                  background: t.is_dnc ? undefined : "var(--accent)",
                  color: t.is_dnc ? undefined : "#fff",
                  borderColor: "transparent",
                }}
              >
                {phase === "dialing" ? "発信中…" : "発信"}
              </button>
              <button onClick={skip}>かけずに次へ</button>
            </div>
          </section>

          {/* ---------------------------------------------- 右: 履歴 */}
          <section
            style={{
              border: "1px solid var(--line)",
              borderRadius: 8,
              padding: 16,
            }}
          >
            <h3 style={{ margin: "0 0 10px", fontSize: 15 }}>過去の架電</h3>
            {item?.history?.length ? (
              <table>
                <thead>
                  <tr>
                    <th>日時</th>
                    <th>結果</th>
                    <th style={{ textAlign: "right" }}>通話</th>
                  </tr>
                </thead>
                <tbody>
                  {item.history.map((h, i) => (
                    <tr key={i}>
                      <td style={{ fontSize: 13 }}>
                        {new Date(h.started_at).toLocaleString("ja-JP")}
                      </td>
                      <td style={{ fontSize: 13 }}>
                        {h.disposition_label ?? h.dial_state}
                      </td>
                      <td
                        style={{
                          textAlign: "right",
                          fontVariantNumeric: "tabular-nums",
                          fontSize: 13,
                        }}
                      >
                        {h.duration_seconds != null ? `${h.duration_seconds}s` : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p style={{ color: "var(--muted)", fontSize: 14 }}>履歴はありません</p>
            )}
          </section>

          {/* ---------------------------------------------- 下: 結果入力 */}
          <section
            style={{
              gridColumn: "1 / -1",
              border: "1px solid var(--line)",
              borderRadius: 8,
              padding: 16,
            }}
          >
            <h3 style={{ margin: "0 0 10px", fontSize: 15 }}>架電結果</h3>
            <div
              style={{
                display: "flex",
                flexWrap: "wrap",
                gap: 8,
                marginBottom: 12,
              }}
            >
              {codes.map((c) => (
                <button
                  key={c.code}
                  onClick={() => record(c)}
                  title={c.isDnc ? "選ぶと再勧誘拒否リストに登録されます" : undefined}
                  style={
                    c.isDnc
                      ? { borderColor: "var(--danger)", color: "var(--danger)" }
                      : undefined
                  }
                >
                  {c.label}
                </button>
              ))}
            </div>
            <textarea
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="メモ（任意）"
              rows={2}
            />
            {/* ★ DNC を選ぶと何が起きるかを事前に書いておく。
                「結果は記録したが拒否リストには入っていない」を防ぐため、
                選択と同時に登録する仕様になっている */}
            <p style={{ color: "var(--muted)", fontSize: 12, marginBottom: 0 }}>
              「再勧誘拒否」を選ぶと、その場で拒否リストに登録され、以後この番号へは発信できなくなります。
            </p>
          </section>
        </div>
      )}
    </main>
  );
}
