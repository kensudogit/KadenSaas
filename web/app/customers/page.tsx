"use client";

/**
 * 顧客一覧。顧客名・電話番号・担当者・架電ボタン。
 *
 * ★ 「0 件」と「まだ取得していない」を区別して表示する。
 *   この設計では、トランザクションの外で DB に触ると RLS が黙って 0 行を返す。
 *   その状態を「顧客がいません」と表示してしまうと、不具合が正常な画面に
 *   見えてしまう。読み込み中・エラー・0 件を別々に出す。
 *
 * ★ 電話番号を一覧に出す。以前は「個人情報を一覧に並べると画面を開いただけで
 *   広範囲が露出する」として詳細画面に隠していたが、架電業務では
 *   一覧から直接かけられることが要件なので、番号は出す。
 *   代わりに、かけたことは必ず call_sessions と監査ログに残る。
 *
 * ★ 架電ボタンは番号のある行にだけ出す。番号の無い顧客にボタンを出しても、
 *   押した先で失敗するだけ。
 *
 * ★ 再勧誘拒否の相手にはボタンを出さない。出しても関門が止めるので実害は
 *   無いが、押してから止められるより、最初から押せないほうが分かりやすい。
 *   ただしこれは表示上の配慮で、拒否の判断は関門（サーバー側）にしかない。
 */

import { useEffect, useState } from "react";
import {
  calls,
  customers as customersApi,
  getToken,
  type CustomerRow,
} from "@/lib/api";

type State =
  | { phase: "loading" }
  | { phase: "error"; message: string }
  | { phase: "ready"; rows: CustomerRow[] };

const STATUS_LABELS: Record<string, string> = {
  new: "未接触",
  contacted: "接触済み",
  qualified: "有望",
  won: "成約",
  lost: "失注",
};

const BLOCK_REASON_LABELS: Record<string, string> = {
  do_not_call: "再勧誘拒否として登録されています",
  outside_hours: "架電可能時間外です",
  outside_weekday: "本日は架電対象外の曜日です",
  holiday: "祝日のため架電しません",
  max_attempts_per_day: "本日の架電上限に達しています",
  max_attempts_total: "通算の架電上限に達しています",
  already_in_flight: "この番号への通話が進行中です",
  dialing_disabled: "発信が停止されています",
  telephony_not_configured: "発信者番号が未設定です",
};

export default function CustomersPage() {
  const [state, setState] = useState<State>({ phase: "loading" });
  const [query, setQuery] = useState("");
  const [dialing, setDialing] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [dialError, setDialError] = useState<string | null>(null);

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

  async function dial(row: CustomerRow) {
    if (!row.phone_id) return;
    setDialing(row.id);
    setNotice(null);
    setDialError(null);
    try {
      const result = await calls.dial(row.phone_id);
      if (result.accepted) {
        setNotice(
          `${row.company_name ?? "顧客"} へ発信を要求しました。架電画面で通話を進めてください`,
        );
      } else {
        // ★ 止められたことはエラーではない。関門が正しく働いた結果なので、
        //   理由をそのまま伝える
        setDialError(
          (result.blockedReason &&
            BLOCK_REASON_LABELS[result.blockedReason]) ??
            result.message ??
            "発信できませんでした",
        );
      }
    } catch (e) {
      setDialError(e instanceof Error ? e.message : "発信できませんでした");
    } finally {
      setDialing(null);
    }
  }

  return (
    <main style={{ maxWidth: 1000, margin: "0 auto", padding: "0 20px 40px" }}>
      <h1 style={{ fontSize: 20, margin: "0 0 4px" }}>顧客リスト</h1>
      <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
        番号のある相手には、この一覧からそのまま発信できます。
      </p>

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
          placeholder="会社名・担当者名・電話番号で検索"
          style={{ maxWidth: 320 }}
        />
        <button type="submit">検索</button>
        {query && (
          <button
            type="button"
            onClick={() => {
              setQuery("");
              load();
            }}
          >
            クリア
          </button>
        )}
      </form>

      {notice && (
        <div style={{ color: "var(--ok)", fontSize: 14, marginBottom: 12 }}>
          {notice}
        </div>
      )}
      {dialError && (
        <div
          role="alert"
          style={{
            border: "1px solid var(--warn)",
            color: "var(--warn)",
            borderRadius: 6,
            padding: "8px 10px",
            marginBottom: 12,
            fontSize: 14,
          }}
        >
          発信は行われませんでした: {dialError}
        </div>
      )}

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
            管理者アカウントであれば、ダッシュボードからサンプルデータを投入できます。
          </p>
        </div>
      )}

      {state.phase === "ready" && state.rows.length > 0 && (
        <>
          <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 0 }}>
            {state.rows.length} 件
          </p>
          <div style={{ overflowX: "auto" }}>
            <table>
              <thead>
                <tr>
                  <th>顧客名</th>
                  <th>電話番号</th>
                  <th>担当者</th>
                  <th>状態</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {state.rows.map((c) => (
                  <tr key={c.id}>
                    <td>
                      {c.company_name ?? "（会社名なし）"}
                      {c.contact_name && (
                        <div style={{ fontSize: 12, color: "var(--muted)" }}>
                          {c.contact_name}
                        </div>
                      )}
                    </td>
                    <td style={{ fontVariantNumeric: "tabular-nums" }}>
                      {c.phone_raw ?? c.phone_e164 ?? "—"}
                      {c.do_not_call && (
                        <div style={{ fontSize: 12, color: "var(--warn)" }}>
                          再勧誘拒否
                        </div>
                      )}
                    </td>
                    <td style={{ fontSize: 13 }}>
                      {c.owner_name ?? (
                        <span style={{ color: "var(--muted)" }}>未割当</span>
                      )}
                    </td>
                    <td style={{ fontSize: 13, color: "var(--muted)" }}>
                      {STATUS_LABELS[c.status] ?? c.status}
                    </td>
                    <td style={{ textAlign: "right" }}>
                      {c.phone_id && !c.do_not_call ? (
                        <button
                          onClick={() => dial(c)}
                          disabled={dialing !== null}
                          style={{
                            background: "var(--accent)",
                            color: "#fff",
                            borderColor: "transparent",
                          }}
                        >
                          {dialing === c.id ? "発信中…" : "架電"}
                        </button>
                      ) : (
                        <span style={{ fontSize: 12, color: "var(--muted)" }}>
                          {c.do_not_call ? "架電不可" : "番号なし"}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </main>
  );
}
