"use client";

/**
 * テナント登録。
 *
 * ★ 誰でも登録できる（公開サインアップ）。運用によっては合言葉を
 *   要求する設定にもできるので、サーバーに問い合わせて出し分ける。
 *   ここで判断せず、requiresToken に従う。
 *
 * ★ 登録しただけでは 1 本も発信できない。発信には発信者番号の設定が要り、
 *   登録処理はそれを作らない。誰でも登録できる以上、この分離が
 *   「誰でも迷惑電話をかけられる基盤」にしないための主軸になる。
 *   利用者にも登録前に伝える（できると誤解させない）。
 *
 * ★ 識別子（slug）はログイン時に毎回入力するものなので、
 *   決める前に「あとで変えられない」ことを伝える。後から気付くと
 *   利用者全員に周知し直すことになる。
 */

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export default function SignupPage() {
  const router = useRouter();
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [requiresToken, setRequiresToken] = useState(false);
  const [token, setToken] = useState("");
  const [tenantName, setTenantName] = useState("");
  const [slug, setSlug] = useState("");
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    fetch(`${API_BASE}/api/v1/signup/available`)
      .then((r) => r.json())
      .then((d) => {
        setEnabled(Boolean(d.enabled));
        setRequiresToken(Boolean(d.requiresToken));
      })
      .catch(() => setEnabled(false));
  }, []);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/api/v1/signup`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          // ★ 合言葉を要求しない設定のときは送らない
          ...(requiresToken ? { "X-Signup-Token": token } : {}),
        },
        body: JSON.stringify({
          tenantName,
          slug,
          email,
          password,
          displayName,
        }),
      });
      const body = await res.json();
      if (!res.ok) {
        setError(body.message ?? "登録できませんでした");
        return;
      }
      setDone(body.slug);
    } catch {
      setError("サーバーに接続できませんでした");
    } finally {
      setBusy(false);
    }
  }

  if (enabled === false) {
    return (
      <main style={{ maxWidth: 420, margin: "14vh auto", padding: "0 20px" }}>
        <h1 style={{ fontSize: 20 }}>組織の登録</h1>
        <p style={{ color: "var(--muted)", fontSize: 14 }}>
          現在、新規登録は受け付けていません。管理者にお問い合わせください。
        </p>
        <a href="/" style={{ color: "var(--accent)", fontSize: 14 }}>
          ログインへ戻る
        </a>
      </main>
    );
  }

  if (done) {
    return (
      <main style={{ maxWidth: 440, margin: "14vh auto", padding: "0 20px" }}>
        <h1 style={{ fontSize: 20 }}>登録しました</h1>
        <p style={{ fontSize: 14, lineHeight: 1.8 }}>
          テナント識別子は <strong>{done}</strong> です。
          <br />
          ログイン時に毎回入力するので、控えておいてください。
        </p>
        <button
          onClick={() => router.push("/")}
          style={{
            background: "var(--accent)",
            color: "#fff",
            borderColor: "transparent",
            marginTop: 8,
          }}
        >
          ログインへ
        </button>
      </main>
    );
  }

  return (
    <main style={{ maxWidth: 420, margin: "8vh auto", padding: "0 20px 40px" }}>
      <h1 style={{ fontSize: 22, marginBottom: 4 }}>組織の登録</h1>
      <p style={{ color: "var(--muted)", marginTop: 0, fontSize: 14 }}>
        組織と、最初の管理者アカウントを作ります。
      </p>

      {/* ★ 登録しただけでは発信できないことを、登録前に伝える。
          あとで「かけられない」と問い合わせになるのを防ぐ。
          そしてこれは制限ではなく、この製品の安全設計そのもの */}
      <p
        style={{
          border: "1px solid var(--line)",
          borderRadius: 8,
          padding: "10px 12px",
          fontSize: 13,
          color: "var(--muted)",
          marginTop: 14,
          marginBottom: 0,
        }}
      >
        登録後すぐに発信はできません。架電には、購入・検証済みの発信者番号を
        管理画面で設定する必要があります。
      </p>

      <form onSubmit={submit} style={{ display: "grid", gap: 14, marginTop: 22 }}>
        {requiresToken && (
          <Field label="登録用トークン" hint="管理者から受け取った値">
            <input
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              required
            />
          </Field>
        )}

        <Field label="組織名">
          <input
            value={tenantName}
            onChange={(e) => setTenantName(e.target.value)}
            placeholder="例: 株式会社サンプル"
            required
          />
        </Field>

        {/* ★ あとで変えられないことを、決める前に伝える */}
        <Field
          label="テナント識別子"
          hint="英小文字・数字・ハイフンで 3〜40 文字。ログイン時に毎回入力します。あとから変更できません"
        >
          <input
            value={slug}
            onChange={(e) => setSlug(e.target.value.toLowerCase())}
            placeholder="例: sample-sales"
            pattern="[a-z0-9][a-z0-9-]{1,38}[a-z0-9]"
            required
          />
        </Field>

        <Field label="管理者のメールアドレス">
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="username"
            required
          />
        </Field>

        <Field label="表示名" hint="省略すると「管理者」になります">
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="例: 山田 太郎"
          />
        </Field>

        <Field label="パスワード" hint="12 文字以上">
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={12}
            autoComplete="new-password"
            required
          />
        </Field>

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

        <button
          type="submit"
          disabled={busy || enabled === null}
          style={{
            background: "var(--accent)",
            color: "#fff",
            borderColor: "transparent",
            marginTop: 4,
          }}
        >
          {busy ? "登録しています…" : "登録する"}
        </button>

        {/* ★ 登録しただけでは電話をかけられないことを先に伝える。
            あとで「鳴らない」と調べ始める時間を省くため */}
        <p style={{ color: "var(--muted)", fontSize: 12, margin: 0 }}>
          登録後、発信を行うには発信者番号の設定が別途必要です。
        </p>

        <a href="/" style={{ color: "var(--accent)", fontSize: 14 }}>
          ログインへ戻る
        </a>
      </form>
    </main>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label>
      <div style={{ fontSize: 13, color: "var(--muted)" }}>{label}</div>
      {children}
      {hint && (
        <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 3 }}>
          {hint}
        </div>
      )}
    </label>
  );
}
