"use client";

/**
 * ログイン。
 *
 * ★ テナント識別子（slug）を入力させる。email だけで所属テナントを
 *   引こうとすると、RLS を迂回する検索が必要になり、その迂回は
 *   いずれ別の用途に流用されてテナント分離の穴になる。
 *   1 項目増える不便より、迂回経路を作らないことを取る。
 *
 * ★ 「テナントが無い」と「パスワードが違う」を区別して表示しない
 *   （サーバー側も区別せずに返している）。区別すると、
 *   テナント名と登録済みアドレスを総当たりで調べられる。
 */

import { useState } from "react";
import { useRouter } from "next/navigation";
import { login } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [tenantSlug, setTenantSlug] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const session = await login(tenantSlug, email, password);
      router.push(session.user.role === "operator" ? "/operator" : "/dashboard");
    } catch (err) {
      setError(err instanceof Error ? err.message : "ログインできませんでした");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main
      style={{
        maxWidth: 380,
        margin: "12vh auto",
        padding: "0 20px",
      }}
    >
      <h1 style={{ fontSize: 22, marginBottom: 4 }}>架電SaaS</h1>
      <p style={{ color: "var(--muted)", marginTop: 0, fontSize: 14 }}>
        テナントの識別子と、担当者のアカウントでログインします
      </p>

      <form onSubmit={submit} style={{ display: "grid", gap: 14, marginTop: 24 }}>
        <label>
          <div style={{ fontSize: 13, color: "var(--muted)" }}>テナント識別子</div>
          <input
            value={tenantSlug}
            onChange={(e) => setTenantSlug(e.target.value)}
            placeholder="例: acme-sales"
            autoComplete="organization"
            required
          />
        </label>

        <label>
          <div style={{ fontSize: 13, color: "var(--muted)" }}>メールアドレス</div>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            required
          />
        </label>

        <label>
          <div style={{ fontSize: 13, color: "var(--muted)" }}>パスワード</div>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>

        {error && (
          <div
            role="alert"
            style={{
              color: "var(--danger)",
              fontSize: 14,
              border: "1px solid var(--danger)",
              borderRadius: 6,
              padding: "8px 10px",
            }}
          >
            {error}
          </div>
        )}

        <button type="submit" disabled={busy} style={{ marginTop: 4 }}>
          {busy ? "確認しています…" : "ログイン"}
        </button>
      </form>
    </main>
  );
}
