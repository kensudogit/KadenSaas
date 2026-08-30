"use client";

/**
 * 電話設定と発信の診断。
 *
 * ★ この画面の主目的は診断のほう。架電が止まる原因は毎回同じ数種類だが、
 *   それぞれ別の場所に出るので、1 箇所に集めないと毎回ログを掘ることになる。
 *   「発信できません」ではなく、項目ごとに何が足りないかを出す。
 *
 * ★ 停止スイッチは設定の保存と分けて置く。事故のときに押すものなので、
 *   他の項目を巻き込まず 1 クリックで確実に止まることを優先する。
 *
 * ★ 発信者番号は「購入・検証済みのものだけ」と明記する。未検証の番号を
 *   入れても保存は通るが、Twilio 側で発信が失敗する。保存できた＝使える、
 *   と誤解させない。
 */

import { useCallback, useEffect, useState } from "react";
import { telephony, getRole, type TelephonyCheck } from "@/lib/api";

type Settings = {
  configured: boolean;
  callerId?: string;
  machineDetection?: string;
  recordingEnabled?: boolean;
  dialingEnabled?: boolean;
};

export default function TelephonySettingsPage() {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [checks, setChecks] = useState<TelephonyCheck[]>([]);
  const [canDial, setCanDial] = useState<boolean | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);

  const [callerId, setCallerId] = useState("");
  const [detection, setDetection] = useState("DetectMessageEnd");
  const [recording, setRecording] = useState(true);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const d = await telephony.diagnose();
      setChecks(d.checks);
      setCanDial(d.canDial);
    } catch (e) {
      setError(e instanceof Error ? e.message : "診断を取得できませんでした");
    }
    if (getRole() === "admin") {
      try {
        const s = await telephony.get();
        setSettings(s);
        if (s.configured) {
          setCallerId(s.callerId ?? "");
          setDetection(s.machineDetection ?? "DetectMessageEnd");
          setRecording(s.recordingEnabled ?? true);
        }
      } catch {
        /* 診断だけ見られればよい */
      }
    }
  }, []);

  useEffect(() => {
    setIsAdmin(getRole() === "admin");
    load();
  }, [load]);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await telephony.save({
        callerId,
        machineDetection: detection,
        recordingEnabled: recording,
        dialingEnabled: settings?.dialingEnabled ?? true,
      });
      setNotice("保存しました");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存できませんでした");
    } finally {
      setBusy(false);
    }
  }

  async function toggleDialing(enabled: boolean) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const r = await telephony.setDialing(enabled);
      setNotice(r.message);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "切り替えできませんでした");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main style={{ maxWidth: 720, margin: "0 auto", padding: 20 }}>
      <header style={{ display: "flex", alignItems: "baseline", marginBottom: 16 }}>
        <h1 style={{ fontSize: 20, margin: 0 }}>電話設定</h1>
        <nav style={{ marginLeft: "auto", display: "flex", gap: 14, fontSize: 14 }}>
          <a href="/dashboard" style={{ color: "var(--accent)" }}>KPI</a>
          <a href="/customers" style={{ color: "var(--accent)" }}>顧客</a>
          <a href="/operator" style={{ color: "var(--accent)" }}>架電</a>
        </nav>
      </header>

      {error && (
        <div
          role="alert"
          style={{
            border: "1px solid var(--danger)",
            color: "var(--danger)",
            borderRadius: 6,
            padding: "8px 10px",
            marginBottom: 14,
            fontSize: 14,
          }}
        >
          {error}
        </div>
      )}
      {notice && (
        <div style={{ color: "var(--ok)", fontSize: 14, marginBottom: 14 }}>
          {notice}
        </div>
      )}

      {/* ---------------------------------------------------- 診断 */}
      <section
        style={{
          border: "1px solid var(--line)",
          borderRadius: 8,
          padding: 16,
          marginBottom: 20,
        }}
      >
        <h2 style={{ fontSize: 16, margin: "0 0 4px" }}>発信できるか</h2>
        <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
          止まっているときは、ここに理由が出ます。
        </p>

        {canDial !== null && (
          <div
            style={{
              display: "inline-block",
              borderRadius: 999,
              padding: "4px 12px",
              fontSize: 13,
              fontWeight: 600,
              marginBottom: 12,
              color: canDial ? "var(--ok)" : "var(--warn)",
              border: `1px solid ${canDial ? "var(--ok)" : "var(--warn)"}`,
            }}
          >
            {canDial ? "設定は整っています" : "設定に不足があります"}
          </div>
        )}

        <div style={{ display: "grid", gap: 8 }}>
          {checks.map((c) => (
            <div
              key={c.key}
              style={{ display: "flex", gap: 10, alignItems: "baseline" }}
            >
              <span
                aria-hidden
                style={{
                  color: c.ok ? "var(--ok)" : "var(--warn)",
                  fontWeight: 700,
                  width: 16,
                }}
              >
                {c.ok ? "✓" : "!"}
              </span>
              <span style={{ width: 150, fontSize: 14 }}>{c.label}</span>
              <span style={{ fontSize: 13, color: "var(--muted)", flex: 1 }}>
                {c.detail}
              </span>
            </div>
          ))}
        </div>

        <button onClick={load} disabled={busy} style={{ marginTop: 12 }}>
          再確認
        </button>
      </section>

      {!isAdmin && (
        <p style={{ color: "var(--muted)", fontSize: 14 }}>
          設定の変更は管理者のみ行えます。
        </p>
      )}

      {isAdmin && (
        <>
          {/* ------------------------------------------------ 停止スイッチ */}
          {settings?.configured && (
            <section
              style={{
                border: `1px solid ${
                  settings.dialingEnabled ? "var(--line)" : "var(--warn)"
                }`,
                borderRadius: 8,
                padding: 16,
                marginBottom: 20,
              }}
            >
              <h2 style={{ fontSize: 16, margin: "0 0 4px" }}>発信の停止</h2>
              <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
                苦情や障害のとき、このテナントの発信だけを即座に止められます。
                デプロイを待つ必要はありません。すでに進行中の通話は切れません。
              </p>
              {settings.dialingEnabled ? (
                <button
                  onClick={() => toggleDialing(false)}
                  disabled={busy}
                  style={{ borderColor: "var(--danger)", color: "var(--danger)" }}
                >
                  発信を停止する
                </button>
              ) : (
                <button
                  onClick={() => toggleDialing(true)}
                  disabled={busy}
                  style={{
                    background: "var(--accent)",
                    color: "#fff",
                    borderColor: "transparent",
                  }}
                >
                  発信を再開する
                </button>
              )}
            </section>
          )}

          {/* ------------------------------------------------ 設定 */}
          <section
            style={{ border: "1px solid var(--line)", borderRadius: 8, padding: 16 }}
          >
            <h2 style={{ fontSize: 16, margin: "0 0 12px" }}>発信者番号と録音</h2>

            <form onSubmit={save} style={{ display: "grid", gap: 14 }}>
              <label>
                <div style={{ fontSize: 13, color: "var(--muted)" }}>発信者番号</div>
                <input
                  value={callerId}
                  onChange={(e) => setCallerId(e.target.value)}
                  placeholder="03-1234-5678 または +81312345678"
                  required
                />
                {/* ★ 保存できた＝使える、と誤解させない */}
                <div
                  style={{ fontSize: 12, color: "var(--muted)", marginTop: 3 }}
                >
                  Twilio で購入済み、または検証済みの番号を入れてください。
                  未検証の番号でも保存はできますが、発信は Twilio 側で失敗します。
                </div>
              </label>

              <label>
                <div style={{ fontSize: 13, color: "var(--muted)" }}>
                  留守番電話の検出
                </div>
                <select
                  value={detection}
                  onChange={(e) => setDetection(e.target.value)}
                >
                  <option value="DetectMessageEnd">
                    メッセージ終了まで待つ（推奨）
                  </option>
                  <option value="Enable">検出のみ</option>
                  <option value="none">検出しない</option>
                </select>
                <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 3 }}>
                  人が出たか機械が出たかで、KPI の「接続」の意味が変わります。
                </div>
              </label>

              <label style={{ display: "flex", gap: 8, alignItems: "flex-start" }}>
                <input
                  type="checkbox"
                  checked={recording}
                  onChange={(e) => setRecording(e.target.checked)}
                  style={{ width: "auto", marginTop: 3 }}
                />
                <span>
                  <div style={{ fontSize: 14 }}>通話を録音する</div>
                  <div style={{ fontSize: 12, color: "var(--muted)" }}>
                    録音の告知・同意・保存期間は法令と契約の対象です。
                    運用の確認を済ませてから有効にしてください。
                  </div>
                </span>
              </label>

              <button
                type="submit"
                disabled={busy}
                style={{
                  background: "var(--accent)",
                  color: "#fff",
                  borderColor: "transparent",
                  justifySelf: "start",
                }}
              >
                {busy ? "保存しています…" : "保存"}
              </button>
            </form>
          </section>
        </>
      )}
    </main>
  );
}
