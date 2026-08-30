"use client";

/**
 * 自分のパスワードを変える。
 *
 * ★ 管理者専用ではない。管理者が発行した初期パスワードのまま使い続けられる
 *   状態を作らないために、全員が使える必要がある。
 *
 * ★ 現在のパスワードを必ず入力させる。サーバー側でも照合している。
 *   照合しないと、席を離れた隙に端末を触った誰かが、そのまま
 *   パスワードを差し替えて本人を締め出せる。
 *
 * ★ 変更後はログインし直させる。トークンは変更前の状態で発行されているため、
 *   そのまま使い続けられると「変えたのに古い資格情報が通る」ことになる。
 */

import { useState } from "react";
import { clearToken, users } from "@/lib/api";

const MIN_LENGTH = 12;

export default function PasswordPage() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const tooShort = next.length > 0 && next.length < MIN_LENGTH;
  const mismatch = confirm.length > 0 && next !== confirm;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (next !== confirm) {
      setError("新しいパスワードが一致しません");
      return;
    }
    setBusy(true);
    try {
      await users.changeOwnPassword(current, next);
      setDone(true);
      // ★ 変更前に発行されたトークンを残さない
      clearToken();
    } catch (e) {
      setError(e instanceof Error ? e.message : "変更できませんでした");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <main style={{ maxWidth: 480, margin: "0 auto", padding: "0 20px 40px" }}>
        <h1 style={{ fontSize: 20 }}>パスワードを変更しました</h1>
        <p style={{ fontSize: 14, color: "var(--muted)" }}>
          新しいパスワードでログインし直してください。
        </p>
        <a href="/" style={{ color: "var(--accent)" }}>
          ログイン画面へ
        </a>
      </main>
    );
  }

  return (
    <main style={{ maxWidth: 480, margin: "0 auto", padding: "0 20px 40px" }}>
      <h1 style={{ fontSize: 20, margin: "0 0 4px" }}>パスワードの変更</h1>
      <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
        管理者から初期パスワードを受け取った場合は、ここで自分だけが知る値に
        変更してください。
      </p>

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

      <form onSubmit={submit} style={{ display: "grid", gap: 14 }}>
        <label>
          <div style={{ fontSize: 13, color: "var(--muted)" }}>
            現在のパスワード
          </div>
          <input
            type="password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>

        <label>
          <div style={{ fontSize: 13, color: "var(--muted)" }}>
            新しいパスワード
          </div>
          <input
            type="password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            autoComplete="new-password"
            required
          />
          <div
            style={{
              fontSize: 12,
              color: tooShort ? "var(--warn)" : "var(--muted)",
              marginTop: 3,
            }}
          >
            {MIN_LENGTH} 文字以上。通話録音と顧客の個人情報を扱うため、
            他のサービスと使い回さないでください。
          </div>
        </label>

        <label>
          <div style={{ fontSize: 13, color: "var(--muted)" }}>
            新しいパスワード（確認）
          </div>
          <input
            type="password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            autoComplete="new-password"
            required
          />
          {mismatch && (
            <div style={{ fontSize: 12, color: "var(--warn)", marginTop: 3 }}>
              一致していません
            </div>
          )}
        </label>

        <button
          type="submit"
          disabled={busy || tooShort || mismatch || !next}
          style={{
            background: "var(--accent)",
            color: "#fff",
            borderColor: "transparent",
            justifySelf: "start",
          }}
        >
          {busy ? "変更しています…" : "変更する"}
        </button>
      </form>
    </main>
  );
}
