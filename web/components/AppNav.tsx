"use client";

/**
 * 画面共通のナビゲーション。
 *
 * ★ これまで各画面が自前で <nav> を書いていたため、画面ごとにリンクの
 *   顔ぶれが違っていた（顧客画面から分析へ行けない、など）。
 *   1 箇所にまとめて、どこからでも同じ場所へ行けるようにする。
 *
 * ★ 権限で出し分ける。ただしこれは「押しても 403 になるものを見せない」
 *   ための配慮であって、権限の実装ではない。ここを書き換えても
 *   サーバーが 403 を返す。判定はサーバーにしかない。
 *
 * ★ 現在地を色ではなく太字と下線で示す。色だけで区別すると、
 *   色覚特性によっては区別できない。
 */

import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { clearToken, getRole, type Role } from "@/lib/api";

type Item = {
  href: string;
  label: string;
  /** 表示してよい役割。省略時は全員 */
  roles?: Role[];
};

const ITEMS: Item[] = [
  { href: "/dashboard", label: "ダッシュボード" },
  { href: "/customers", label: "顧客リスト" },
  { href: "/operator", label: "架電" },
  { href: "/history", label: "架電履歴" },
  { href: "/analytics", label: "分析", roles: ["manager", "admin"] },
  { href: "/settings/telephony", label: "電話設定", roles: ["manager", "admin"] },
  { href: "/settings/users", label: "管理", roles: ["admin"] },
];

export default function AppNav() {
  const pathname = usePathname();
  const [role, setRole] = useState<Role | null>(null);

  useEffect(() => {
    setRole(getRole());
  }, []);

  // ★ ログイン前と登録画面では出さない
  if (pathname === "/" || pathname === "/signup") return null;

  const visible = ITEMS.filter((i) => !i.roles || (role && i.roles.includes(role)));

  return (
    <header
      style={{
        borderBottom: "1px solid var(--line)",
        marginBottom: 20,
        background: "var(--panel, transparent)",
      }}
    >
      <div
        style={{
          maxWidth: 1100,
          margin: "0 auto",
          padding: "10px 20px",
          display: "flex",
          alignItems: "center",
          gap: 18,
          flexWrap: "wrap",
        }}
      >
        <span style={{ fontWeight: 700, fontSize: 15, letterSpacing: 0.2 }}>
          架電 SaaS
        </span>

        <nav style={{ display: "flex", gap: 16, flexWrap: "wrap" }}>
          {visible.map((item) => {
            const active =
              pathname === item.href || pathname.startsWith(item.href + "/");
            return (
              <a
                key={item.href}
                href={item.href}
                aria-current={active ? "page" : undefined}
                style={{
                  fontSize: 14,
                  color: active ? "var(--fg)" : "var(--accent)",
                  fontWeight: active ? 700 : 400,
                  textDecoration: active ? "underline" : "none",
                  textUnderlineOffset: 4,
                }}
              >
                {item.label}
              </a>
            );
          })}
        </nav>

        <div
          style={{
            marginLeft: "auto",
            display: "flex",
            alignItems: "center",
            gap: 12,
          }}
        >
          {role && (
            <span style={{ fontSize: 12, color: "var(--muted)" }}>
              {{ operator: "オペレーター", manager: "マネージャー", admin: "管理者" }[
                role
              ]}
            </span>
          )}
          <a href="/settings/password" style={{ fontSize: 13, color: "var(--accent)" }}>
            パスワード
          </a>
          <button
            onClick={() => {
              clearToken();
              window.location.href = "/";
            }}
            style={{ fontSize: 13, padding: "3px 10px" }}
          >
            ログアウト
          </button>
        </div>
      </div>
    </header>
  );
}
