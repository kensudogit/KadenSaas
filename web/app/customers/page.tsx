"use client";

/**
 * 顧客一覧。
 *
 * ★ 「0 件」と「まだ取得していない」を区別して表示する。
 *   この設計では、トランザクションの外で DB に触ると RLS が黙って 0 行を返す。
 *   その状態を「顧客がいません」と表示してしまうと、不具合が正常な画面に
 *   見えてしまう。読み込み中・エラー・0 件を別々に出す。
 *
 * ★ 電話番号は一覧に出さない。個人情報を一覧に並べると、画面を開いただけで
 *   広範囲が露出する。詳細を開いた人にだけ見せる。
 */

import { useEffect, useState } from "react";
import { customers as customersApi, getToken } from "@/lib/api";

type Customer = {
  id: string;
  companyName: string | null;
  contactName: string | null;
  status: string;
};

type State =
  | { phase: "loading" }
  | { phase: "error"; message: string }
  | { phase: "ready"; rows: Customer[] };

const STATUS_LABELS: Record<string, string> = {
  new: "未接触",
  contacted: "接触済み",
  qualified: "有望",
  won: "成約",
  lost: "失注",
};

export default function CustomersPage() {
  const [state, setState] = useState<State>({ phase: "loading" });
  const [query, setQuery] = useState("");

  async function load(q?: string) {
    setState({ phase: "loading" });
    try {
      const rows = await customersApi.list(q);
      setState({ phase: "ready", rows });
    } catch (e) {
      setState({
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
    load();
  }, []);

  return (
    <main style={{ maxWidth: 900, margin: "0 auto", padding: 20 }}>
      <header
        style={{ display: "flex", alignItems: "baseline", gap: 12, marginBottom: 16 }}
      >
        <h1 style={{ fontSize: 20, margin: 0 }}>顧客</h1>
        <nav style={{ marginLeft: "auto", display: "flex", gap: 14, fontSize: 14 }}>
          <a href="/dashboard" style={{ color: "var(--accent)" }}>
            KPI
          </a>
          <a href="/operator" style={{ color: "var(--accent)" }}>
            架電
          </a>
        </nav>
      </header>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          load(query || undefined);
        }}
        style={{ display: "flex", gap: 8, marginBottom: 16 }}
      >
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="会社名・担当者名で検索"
        />
        <button type="submit">検索</button>
      </form>

      {state.phase === "loading" && (
        <p style={{ color: "var(--muted)" }}>読み込んでいます…</p>
      )}

      {state.phase === "error" && (
        <div
          role="alert"
          style={{
            border: "1px solid var(--danger)",
            color: "var(--danger)",
            borderRadius: 6,
            padding: "10px 12px",
          }}
        >
          {state.message}
        </div>
      )}

      {state.phase === "ready" && state.rows.length === 0 && (
        <div
          style={{
            border: "1px dashed var(--line)",
            borderRadius: 8,
            padding: "24px 20px",
            textAlign: "center",
            color: "var(--muted)",
          }}
        >
          <p style={{ margin: "0 0 8px" }}>顧客が登録されていません。</p>
          <p style={{ margin: 0, fontSize: 13 }}>
            管理者アカウントであれば、KPI 画面からサンプルデータを投入できます。
          </p>
        </div>
      )}

      {state.phase === "ready" && state.rows.length > 0 && (
        <>
          <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
            {state.rows.length} 件
          </p>
          <table>
            <thead>
              <tr>
                <th>会社名</th>
                <th>担当者</th>
                <th>状態</th>
              </tr>
            </thead>
            <tbody>
              {state.rows.map((c) => (
                <tr key={c.id}>
                  <td>{c.companyName ?? "（会社名なし）"}</td>
                  <td>{c.contactName ?? "—"}</td>
                  <td style={{ fontSize: 13, color: "var(--muted)" }}>
                    {STATUS_LABELS[c.status] ?? c.status}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </main>
  );
}
