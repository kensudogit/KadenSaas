"use client";

/**
 * 管理：ユーザーと権限。
 *
 * ★ 初期パスワードは発行時に一度だけ表示する。再表示はできない。
 *   画面にも保存しない。忘れた場合は再発行になる。
 *   「後から見られる」ようにすると、管理画面が平文パスワードの
 *   保管庫になってしまう。
 *
 * ★ 削除ではなく無効化しかできない。通話履歴と監査ログが担当者を
 *   参照しているので、行を消すと「誰がかけたか分からない通話」が残る。
 *
 * ★ 権限の表はサーバーの一覧をそのまま出す。画面側で表を持たない。
 *   持つと、実装を変えたときに画面の表だけが古いまま残り、
 *   「書いてあるのに違う」といういちばん質の悪い嘘になる。
 */

import { useCallback, useEffect, useState } from "react";
import {
  admin as adminApi,
  getRole,
  getToken,
  permissions as permissionsApi,
  users as usersApi,
  type CapabilityRow,
  type Role,
  type UserRow,
} from "@/lib/api";

const ROLE_LABELS: Record<Role, string> = {
  operator: "オペレーター",
  manager: "マネージャー",
  admin: "管理者",
};

const ROLE_HINTS: Record<Role, string> = {
  operator: "架電する人。自分の通話だけを見られます",
  manager: "リストと結果を見る人。分析と担当者別の成績を見られます",
  admin: "設定を変える人。利用者と電話設定を扱えます",
};

export default function UsersPage() {
  const [rows, setRows] = useState<UserRow[]>([]);
  const [capabilities, setCapabilities] = useState<CapabilityRow[]>([]);
  const [myRole, setMyRole] = useState<Role | null>(null);

  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState<Role>("operator");

  const [issued, setIssued] = useState<{ email: string; password: string } | null>(
    null,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmClear, setConfirmClear] = useState(false);

  const isAdmin = myRole === "admin";

  const load = useCallback(async () => {
    setError(null);
    try {
      const p = await permissionsApi.get();
      setCapabilities(p.capabilities);
      setMyRole(p.myRole);
    } catch (e) {
      setError(e instanceof Error ? e.message : "権限一覧を取得できませんでした");
    }
    if (getRole() === "admin") {
      try {
        setRows(await usersApi.list());
      } catch (e) {
        setError(e instanceof Error ? e.message : "利用者を取得できませんでした");
      }
    }
  }, []);

  useEffect(() => {
    if (!getToken()) {
      window.location.href = "/";
      return;
    }
    load();
  }, [load]);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setNotice(null);
    setIssued(null);
    try {
      const r = await usersApi.create(email, displayName, role);
      setIssued({ email: r.email, password: r.initialPassword });
      setEmail("");
      setDisplayName("");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "追加できませんでした");
    } finally {
      setBusy(false);
    }
  }

  async function act(fn: () => Promise<{ message: string }>) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const r = await fn();
      setNotice(r.message);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "変更できませんでした");
    } finally {
      setBusy(false);
    }
  }

  async function resetPassword(u: UserRow) {
    setBusy(true);
    setError(null);
    setNotice(null);
    setIssued(null);
    try {
      const r = await usersApi.resetPassword(u.id);
      setIssued({ email: u.email, password: r.initialPassword });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "再発行できませんでした");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main style={{ maxWidth: 1000, margin: "0 auto", padding: "0 20px 40px" }}>
      <h1 style={{ fontSize: 20, margin: "0 0 16px" }}>管理</h1>

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

      {/* -------------------------------------------------- 発行された初期パスワード */}
      {issued && (
        <div
          style={{
            border: "2px solid var(--warn)",
            borderRadius: 8,
            padding: "14px 16px",
            marginBottom: 20,
          }}
        >
          <h2 style={{ fontSize: 15, margin: "0 0 6px" }}>
            初期パスワードを発行しました
          </h2>
          <p style={{ fontSize: 13, color: "var(--muted)", margin: "0 0 10px" }}>
            この画面を離れると二度と表示されません。本人に安全な経路で渡してください。
            本人には最初のログイン後に変更してもらいます。
          </p>
          <div style={{ fontSize: 13, marginBottom: 4 }}>{issued.email}</div>
          <code
            style={{
              display: "inline-block",
              fontSize: 17,
              letterSpacing: 1,
              padding: "6px 12px",
              border: "1px solid var(--line)",
              borderRadius: 6,
              userSelect: "all",
            }}
          >
            {issued.password}
          </code>
          <div style={{ marginTop: 10 }}>
            <button onClick={() => setIssued(null)}>閉じる</button>
          </div>
        </div>
      )}

      {!isAdmin && (
        <p style={{ color: "var(--muted)", fontSize: 14 }}>
          利用者の管理は管理者のみ行えます。下の権限一覧は全員が確認できます。
        </p>
      )}

      {isAdmin && (
        <>
          {/* ---------------------------------------------- 追加 */}
          <section
            style={{
              border: "1px solid var(--line)",
              borderRadius: 8,
              padding: 16,
              marginBottom: 22,
            }}
          >
            <h2 style={{ fontSize: 16, margin: "0 0 4px" }}>利用者を追加</h2>
            <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
              初期パスワードはシステムが生成します。管理者が考える必要はありません。
            </p>
            <form
              onSubmit={create}
              style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "flex-end" }}
            >
              <label style={{ fontSize: 13, flex: 1, minWidth: 200 }}>
                <div style={{ color: "var(--muted)" }}>メールアドレス</div>
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </label>
              <label style={{ fontSize: 13, flex: 1, minWidth: 150 }}>
                <div style={{ color: "var(--muted)" }}>表示名</div>
                <input
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder="省略可"
                />
              </label>
              <label style={{ fontSize: 13 }}>
                <div style={{ color: "var(--muted)" }}>役割</div>
                <select
                  value={role}
                  onChange={(e) => setRole(e.target.value as Role)}
                >
                  {(Object.keys(ROLE_LABELS) as Role[]).map((r) => (
                    <option key={r} value={r}>
                      {ROLE_LABELS[r]}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="submit"
                disabled={busy}
                style={{
                  background: "var(--accent)",
                  color: "#fff",
                  borderColor: "transparent",
                }}
              >
                {busy ? "処理中…" : "追加"}
              </button>
            </form>
            <p style={{ fontSize: 12, color: "var(--muted)", margin: "8px 0 0" }}>
              {ROLE_HINTS[role]}
            </p>
          </section>

          {/* ---------------------------------------------- 一覧 */}
          <section style={{ marginBottom: 30 }}>
            <h2 style={{ fontSize: 16, marginBottom: 2 }}>利用者</h2>
            <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
              退職者は無効化してください。削除はできません（通話履歴の担当者が
              辿れなくなるため）。
            </p>
            <div style={{ overflowX: "auto" }}>
              <table>
                <thead>
                  <tr>
                    <th>名前</th>
                    <th>役割</th>
                    <th>状態</th>
                    <th style={{ textAlign: "right" }}>架電</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((u) => (
                    <tr key={u.id}>
                      <td>
                        {u.display_name}
                        <div style={{ fontSize: 12, color: "var(--muted)" }}>
                          {u.email}
                        </div>
                        {u.password_change_required && (
                          <div style={{ fontSize: 12, color: "var(--warn)" }}>
                            初期パスワードのまま
                          </div>
                        )}
                      </td>
                      <td>
                        <select
                          value={u.role}
                          disabled={busy}
                          onChange={(e) =>
                            act(() =>
                              usersApi.changeRole(u.id, e.target.value as Role),
                            )
                          }
                          style={{ fontSize: 13 }}
                        >
                          {(Object.keys(ROLE_LABELS) as Role[]).map((r) => (
                            <option key={r} value={r}>
                              {ROLE_LABELS[r]}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td style={{ fontSize: 13 }}>
                        {u.status === "active" ? (
                          "有効"
                        ) : (
                          <span style={{ color: "var(--muted)" }}>無効</span>
                        )}
                      </td>
                      <td
                        style={{
                          textAlign: "right",
                          fontVariantNumeric: "tabular-nums",
                          fontSize: 13,
                        }}
                      >
                        {u.call_count}
                      </td>
                      <td style={{ textAlign: "right", whiteSpace: "nowrap" }}>
                        <button
                          onClick={() => resetPassword(u)}
                          disabled={busy}
                          style={{ fontSize: 13, marginRight: 6 }}
                        >
                          パスワード再発行
                        </button>
                        <button
                          onClick={() =>
                            act(() =>
                              usersApi.setStatus(u.id, u.status !== "active"),
                            )
                          }
                          disabled={busy}
                          style={{ fontSize: 13 }}
                        >
                          {u.status === "active" ? "無効にする" : "有効にする"}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}

      {/* -------------------------------------------------- サンプルデータ */}
      {isAdmin && (
        <section
          style={{
            border: "1px solid var(--line)",
            borderRadius: 8,
            padding: 16,
            marginBottom: 30,
          }}
        >
          <h2 style={{ fontSize: 16, margin: "0 0 4px" }}>サンプルデータ</h2>
          <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
            動作確認用の顧客・架電履歴を投入します。電話番号は実在しない
            03-1234-5xxx を使うので、誤って発信されることはありません。
          </p>

          <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
            <button
              onClick={() =>
                act(async () => {
                  const r = await adminApi.generateSampleData(false);
                  return {
                    message:
                      r.message +
                      `（顧客 ${r.customers} 件 / 通話 ${r.callSessions} 件）`,
                  };
                })
              }
              disabled={busy}
            >
              {busy ? "処理中…" : "投入する"}
            </button>

            {/* ★ 削除は 2 段階。顧客も通話履歴も消えるので、
                1 クリックで実行できる場所に置かない */}
            {confirmClear ? (
              <>
                <button
                  onClick={() =>
                    act(async () => {
                      const r = await adminApi.clearData();
                      setConfirmClear(false);
                      return { message: r.message };
                    })
                  }
                  disabled={busy}
                  style={{ borderColor: "var(--danger)", color: "var(--danger)" }}
                >
                  本当に全部削除する
                </button>
                <button onClick={() => setConfirmClear(false)} disabled={busy}>
                  やめる
                </button>
              </>
            ) : (
              <button onClick={() => setConfirmClear(true)} disabled={busy}>
                すべて削除
              </button>
            )}
          </div>

          <p style={{ fontSize: 12, color: "var(--warn)", margin: "10px 0 0" }}>
            「すべて削除」は顧客・架電履歴・キャンペーンをすべて消します。
            本番のデータが入っている環境では使わないでください。
          </p>
        </section>
      )}

      {/* -------------------------------------------------- 権限 */}
      <section>
        <h2 style={{ fontSize: 16, marginBottom: 2 }}>権限</h2>
        <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
          この表はサーバーの設定をそのまま表示しています。表示と実際の挙動が
          一致していることは、自動テストが実際に各 API を叩いて確認しています。
        </p>
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead>
              <tr>
                <th>できること</th>
                <th style={{ textAlign: "center" }}>オペレーター</th>
                <th style={{ textAlign: "center" }}>マネージャー</th>
                <th style={{ textAlign: "center" }}>管理者</th>
              </tr>
            </thead>
            <tbody>
              {capabilities.map((c) => (
                <tr key={c.key}>
                  <td>
                    {c.label}
                    <div style={{ fontSize: 12, color: "var(--muted)" }}>
                      {c.detail}
                    </div>
                  </td>
                  <Cell on={c.operator} highlight={myRole === "operator"} />
                  <Cell on={c.manager} highlight={myRole === "manager"} />
                  <Cell on={c.admin} highlight={myRole === "admin"} />
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </main>
  );
}

/** ★ 記号と文字の両方で示す。色だけだと区別できない人がいる。 */
function Cell({ on, highlight }: { on: boolean; highlight: boolean }) {
  return (
    <td
      style={{
        textAlign: "center",
        background: highlight ? "var(--panel)" : undefined,
        color: on ? "var(--ok)" : "var(--muted)",
        fontWeight: on ? 700 : 400,
      }}
    >
      <span aria-hidden>{on ? "✓" : "—"}</span>
      <span
        style={{
          position: "absolute",
          width: 1,
          height: 1,
          overflow: "hidden",
          clip: "rect(0 0 0 0)",
        }}
      >
        {on ? "可" : "不可"}
      </span>
    </td>
  );
}
