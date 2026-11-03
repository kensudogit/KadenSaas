"use client";

/**
 * 画面共通のナビゲーション。
 *
 * ★ 自動架電（AI / オートダイヤル / IVR）は <details> にまとめ、ヘッダーの項目数を抑える。
 */

import { usePathname } from "next/navigation";
import { useEffect, useState, type CSSProperties } from "react";
import { clearToken, getRole, type Role } from "@/lib/api";
import BrandLogo from "@/components/BrandLogo";

type Item = {
  href: string;
  label: string;
  roles?: Role[];
};

const PRIMARY: Item[] = [
  { href: "/dashboard", label: "ダッシュボード" },
  { href: "/customers", label: "顧客リスト" },
  { href: "/operator", label: "架電" },
  { href: "/call-center", label: "コールセンター" },
  { href: "/history", label: "架電履歴" },
  { href: "/analytics", label: "分析", roles: ["manager", "admin"] },
  { href: "/settings/telephony", label: "電話設定", roles: ["manager", "admin"] },
  { href: "/settings/users", label: "管理", roles: ["admin"] },
];

const AUTO_OUTBOUND: Item[] = [
  { href: "/ai-outbound", label: "AI 架電" },
  { href: "/auto-dialer", label: "オートダイヤル" },
  { href: "/ivr-outbound", label: "IVR 架電" },
];

const linkStyle = (active: boolean): CSSProperties => ({
  fontSize: 14,
  color: active ? "var(--fg)" : "var(--accent)",
  fontWeight: active ? 700 : 400,
  textDecoration: active ? "underline" : "none",
  textUnderlineOffset: 4,
});

export default function AppNav() {
  const pathname = usePathname();
  const [role, setRole] = useState<Role | null>(null);

  useEffect(() => {
    setRole(getRole());
  }, []);

  if (pathname === "/" || pathname === "/signup") return null;

  const visiblePrimary = PRIMARY.filter(
    (i) => !i.roles || (role && i.roles.includes(role)),
  );
  const autoActive = AUTO_OUTBOUND.some(
    (i) => pathname === i.href || pathname.startsWith(i.href + "/"),
  );

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
        <a
          href="/dashboard"
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
            color: "inherit",
            textDecoration: "none",
            fontWeight: 700,
            fontSize: 15,
            letterSpacing: 0.2,
          }}
        >
          <BrandLogo size={32} />
          架電 SaaS
        </a>

        <nav
          style={{
            display: "flex",
            gap: 16,
            flexWrap: "wrap",
            alignItems: "center",
          }}
        >
          {visiblePrimary.slice(0, 3).map((item) => {
            const active =
              pathname === item.href || pathname.startsWith(item.href + "/");
            return (
              <a key={item.href} href={item.href} style={linkStyle(active)}>
                {item.label}
              </a>
            );
          })}

          <details style={{ position: "relative" }}>
            <summary
              style={{
                ...linkStyle(autoActive),
                cursor: "pointer",
                listStyle: "none",
              }}
            >
              自動架電 ▾
            </summary>
            <div
              style={{
                position: "absolute",
                top: "100%",
                left: 0,
                marginTop: 6,
                padding: "8px 0",
                minWidth: 160,
                background: "var(--panel, #1a1a1a)",
                border: "1px solid var(--line)",
                borderRadius: 8,
                zIndex: 50,
                display: "flex",
                flexDirection: "column",
                gap: 4,
              }}
            >
              {AUTO_OUTBOUND.map((item) => {
                const active =
                  pathname === item.href ||
                  pathname.startsWith(item.href + "/");
                return (
                  <a
                    key={item.href}
                    href={item.href}
                    style={{
                      ...linkStyle(active),
                      padding: "6px 14px",
                      display: "block",
                    }}
                  >
                    {item.label}
                  </a>
                );
              })}
            </div>
          </details>

          {visiblePrimary.slice(3).map((item) => {
            const active =
              pathname === item.href || pathname.startsWith(item.href + "/");
            return (
              <a key={item.href} href={item.href} style={linkStyle(active)}>
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
