"use client";

/**
 * 利用手順パレット。
 *
 * ドラッグで移動でき、折りたためる浮動パネル。画面を開いたまま
 * 手順を確認できるようにするのが目的なので、本文の上に固定で居座らず、
 * 邪魔になったら畳める・どかせる、を優先している。
 *
 * ★ 位置を localStorage に覚える。毎回同じ場所に出て、毎回どかす、を
 *   繰り返させない。畳んだ状態も覚える。
 *
 * ★ 画面外に出ない。ドラッグ位置をビューポート内に丸めてある。
 *   一度でも外に出ると、掴み直せなくなって二度と戻せない。
 *
 * ★ 中身は「読む順」に並べる。まず全体像、次に構成、最後に手順。
 *   手順書は最初の 3 行で「これは何の話か」が分からないと読まれない。
 */

import { useCallback, useEffect, useRef, useState } from "react";

const POS_KEY = "kaden.guide.pos";
const OPEN_KEY = "kaden.guide.open";

type Pos = { x: number; y: number };

export default function GuidePalette() {
  const [open, setOpen] = useState(true);
  const [pos, setPos] = useState<Pos>({ x: 24, y: 24 });
  const [mounted, setMounted] = useState(false);
  const dragRef = useRef<{ dx: number; dy: number } | null>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  // ---------------------------------------------------------------- 復元

  useEffect(() => {
    try {
      const raw = localStorage.getItem(POS_KEY);
      if (raw) setPos(clamp(JSON.parse(raw)));
      setOpen(localStorage.getItem(OPEN_KEY) !== "false");
    } catch {
      // 壊れた値なら既定位置で出す
    }
    setMounted(true);
  }, []);

  /** ★ ビューポート内に丸める。外に出ると掴み直せなくなる。 */
  function clamp(p: Pos): Pos {
    if (typeof window === "undefined") return p;
    const w = panelRef.current?.offsetWidth ?? 440;
    return {
      x: Math.min(Math.max(0, p.x), Math.max(0, window.innerWidth - w)),
      y: Math.min(Math.max(0, p.y), Math.max(0, window.innerHeight - 80)),
    };
  }

  // ---------------------------------------------------------------- ドラッグ

  const onMove = useCallback((e: PointerEvent) => {
    if (!dragRef.current) return;
    setPos(
      clamp({ x: e.clientX - dragRef.current.dx, y: e.clientY - dragRef.current.dy }),
    );
  }, []);

  const onUp = useCallback(() => {
    if (!dragRef.current) return;
    dragRef.current = null;
    window.removeEventListener("pointermove", onMove);
    window.removeEventListener("pointerup", onUp);
    setPos((p) => {
      try {
        localStorage.setItem(POS_KEY, JSON.stringify(p));
      } catch {
        // 保存できなくても動作には影響しない
      }
      return p;
    });
  }, [onMove]);

  function startDrag(e: React.PointerEvent) {
    const rect = panelRef.current?.getBoundingClientRect();
    if (!rect) return;
    dragRef.current = { dx: e.clientX - rect.left, dy: e.clientY - rect.top };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  }

  function toggle() {
    setOpen((v) => {
      try {
        localStorage.setItem(OPEN_KEY, String(!v));
      } catch {
        /* noop */
      }
      return !v;
    });
  }

  // ★ サーバー側では位置が決まらない。描画を待ってから出す
  if (!mounted) return null;

  return (
    <div
      ref={panelRef}
      style={{
        position: "fixed",
        left: pos.x,
        top: pos.y,
        width: 440,
        maxWidth: "calc(100vw - 24px)",
        zIndex: 60,
        borderRadius: 18,
        background: "var(--gp-bg)",
        border: "1px solid var(--gp-line)",
        boxShadow: "0 18px 50px rgba(60, 40, 130, .18)",
        overflow: "hidden",
        fontSize: 14,
      }}
    >
      {/* -------------------------------------------------- ヘッダ */}
      <div
        onPointerDown={startDrag}
        style={{
          display: "flex",
          alignItems: "center",
          gap: 10,
          padding: "12px 14px",
          cursor: "grab",
          userSelect: "none",
          background: "var(--gp-head)",
          borderBottom: open ? "1px solid var(--gp-line)" : "none",
        }}
      >
        <span style={{ color: "var(--gp-muted)", fontSize: 16 }}>☰</span>
        <span
          style={{
            width: 4,
            height: 20,
            borderRadius: 2,
            background: "var(--gp-accent)",
          }}
        />
        <div style={{ lineHeight: 1.15 }}>
          <div style={{ fontWeight: 700, fontSize: 16 }}>利用手順</div>
          <div
            style={{
              fontSize: 10,
              letterSpacing: ".12em",
              color: "var(--gp-muted)",
              fontWeight: 600,
            }}
          >
            KADEN SAAS · SETUP &amp; OPS
          </div>
        </div>
        <span
          style={{
            marginLeft: 10,
            fontSize: 12,
            color: "var(--gp-accent)",
            fontWeight: 600,
          }}
        >
          ドラッグで移動
        </span>
        <button
          onClick={toggle}
          onPointerDown={(e) => e.stopPropagation()}
          aria-label={open ? "閉じる" : "開く"}
          style={{
            marginLeft: "auto",
            width: 30,
            height: 30,
            borderRadius: 999,
            border: "1px solid var(--gp-line)",
            background: "var(--gp-chip)",
            color: "var(--gp-accent)",
            display: "grid",
            placeItems: "center",
            padding: 0,
            lineHeight: 1,
          }}
        >
          {open ? "▾" : "▸"}
        </button>
      </div>

      {open && (
        <div
          style={{
            padding: 14,
            display: "grid",
            gap: 12,
            maxHeight: "min(72vh, 760px)",
            overflowY: "auto",
          }}
        >
          <Card>
            <Label>KADEN SAAS · 架電特化型SaaS</Label>
            <h3 style={{ margin: "6px 0 6px", fontSize: 17 }}>
              発信から KPI までを一本の線で扱う
            </h3>
            <p style={{ margin: 0, color: "var(--gp-body)", lineHeight: 1.75 }}>
              顧客リスト → 発信 → 通話 → 録音 → 文字起こし → AI要約 →
              架電結果 → 再架電 → KPI。テナント分離は PostgreSQL の RLS。
            </p>
            <Tags
              items={[
                "Next.js · React",
                "Spring Boot · 業務API",
                "FastAPI · 音声/AI",
                "PostgreSQL · RLS",
                "Twilio · Media Streams",
                "Claude · 通話分析",
                "Railway / AWS",
              ]}
            />
          </Card>

          <Card>
            <Badge>ARCHITECTURE</Badge>
            <p style={{ margin: "8px 0", color: "var(--gp-body)", lineHeight: 1.75 }}>
              バックエンドは 2 本。<strong>Twilio に触れるのは voice だけ</strong>で、
              スキーマの所有者は api（Flyway）。1 つの PostgreSQL を RLS で共有します。
            </p>
            <Bullets
              items={[
                <>
                  <b>api</b>（Spring Boot） — 顧客・リスト・結果・KPI・課金。
                  <b>発信の関門</b>を持つ
                </>,
                <>
                  <b>voice-web</b>（FastAPI） — Twilio の webhook と内部 API
                </>,
                <>
                  <b>voice-media</b> — Media Streams の WebSocket。
                  <b>必ず別サービス</b>で動かす
                </>,
                <>
                  <b>voice-jobs</b> — 録音の取得・保存期限切れの削除・AI 分析。
                  公開しない
                </>,
                <>
                  <b>web</b>（Next.js） — 画面
                </>,
              ]}
            />
          </Card>

          <SectionLabel>SERVICE TOPOLOGY</SectionLabel>
          <Code>{`Browser (Next.js)
   │ HTTPS + JWT
   ├─→ api      :8080   業務 API / スキーマ所有
   │              └─ PostgreSQL (RLS)
   └─→ voice-web:8001   /twilio/*  /internal/*
                  ├─ voice-media   /media  (WebSocket)
                  ├─ voice-jobs    録音 / 文字起こし / AI
                  └─ S3 · Redis · Twilio · Claude`}</Code>

          <SectionLabel>SETUP — ローカル</SectionLabel>
          <Card>
            <Steps
              items={[
                <>
                  <Code inline>docker compose up -d</Code> で 6 サービスが起動。
                  初回は api が Flyway を流すため 1 分ほどかかります
                </>,
                <>
                  <Code inline>sh scripts/smoke-test.sh</Code> で 18 項目を確認。
                  「すべて通りました」が出れば準備完了
                </>,
                <>
                  <a href="http://localhost:3000" style={linkStyle}>
                    localhost:3000
                  </a>
                  でログイン。テナント <Code inline>demo</Code> /
                  <Code inline>operator@demo.example</Code> /
                  <Code inline>password</Code>
                </>,
              ]}
            />
          </Card>

          <SectionLabel>SETUP — 本番（Railway）</SectionLabel>
          <Card>
            <Steps
              items={[
                <>
                  サービスを 5 つ作り、それぞれ <b>Dockerfile Path</b> を指定。
                  ルートに Dockerfile は無いので、指定しないとビルドできません
                </>,
                <>
                  <b>voice-jobs にドメインを付けない。</b>HTTP を持たないため、
                  ヘルスチェックが永久に通らず「Deploying のまま」になります
                </>,
                <>
                  <Code inline>db/bootstrap-roles.sql</Code> で
                  <Code inline>kaden_app</Code> を作り、
                  <Code inline>DATABASE_URL</Code> をそこへ向ける。
                  <b>既定の superuser のままだと RLS が効かず起動を拒否します</b>
                </>,
                <>
                  <Code inline>JWT_SECRET</Code> は
                  <Code inline>{"${{KadenSaas.JWT_SECRET}}"}</Code> の参照で繋ぐ。
                  値がずれると「api では通るのに voice で 401」になります
                </>,
              ]}
            />
          </Card>

          <SectionLabel>使いはじめ</SectionLabel>
          <Card>
            <Steps
              items={[
                <>
                  <b>組織を登録</b> — ログイン画面の「組織を登録する」。
                  <Code inline>KADEN_SIGNUP_TOKEN</Code> を設定したときだけ使えます
                </>,
                <>
                  <b>サンプルを投入</b> — KPI 画面の「サンプルデータを投入」（管理者のみ）。
                  電話番号は実在しない 03-1234-5xxx なので誤発信しません
                </>,
                <>
                  <b>架電する</b> — 架電画面でキャンペーン ID を入れて
                  「次の 1 件を受け取る」
                </>,
              ]}
            />
          </Card>

          <SectionLabel>つまずいたら</SectionLabel>
          <Card>
            <Bullets
              items={[
                <>
                  <b>一覧が空。DB にはデータがある</b> —
                  トランザクションの外で DB に触っています。RLS が黙って 0 行を返します
                </>,
                <>
                  <b>Webhook が全件 403</b> —
                  <Code inline>PUBLIC_BASE_URL</Code> と Twilio Console の URL が
                  1 文字ずれています。末尾のスラッシュでも起きます
                </>,
                <>
                  <b>発信が止まる</b> — 多くは正常です。DNC・架電時間帯・曜日・
                  回数上限のいずれか。理由は KPI 画面の「関門が止めた理由」に出ます
                </>,
                <>
                  <b>コールリストが枯れる</b> —
                  予約の期限切れ解放ジョブが動いていません
                </>,
              ]}
            />
          </Card>

          <p
            style={{
              margin: 0,
              fontSize: 12,
              color: "var(--gp-muted)",
              lineHeight: 1.7,
            }}
          >
            詳細は README の「トランザクションと RLS」「症状から引く」を参照。
            架電業務は法令の対象です。実サービス化の前に、対象地域・業種について
            専門家または担当部門の確認を行ってください。
          </p>
        </div>
      )}

      {/* ★ 配色はここに閉じ込める。ライト／ダークの両方で読めるようにし、
          アプリ本体のトークンとは別系統にして、パレットだけ差し替えられるようにする */}
      <style>{`
        :root {
          --gp-bg: #ffffff;
          --gp-head: #faf8ff;
          --gp-card: #f7f4ff;
          --gp-line: #e6e0f5;
          --gp-accent: #6d4aff;
          --gp-chip: #ede8ff;
          --gp-body: #3b3555;
          --gp-muted: #8b83a6;
          --gp-code-bg: #14121d;
          --gp-code-fg: #d9d4ee;
        }
        @media (prefers-color-scheme: dark) {
          :root:not([data-theme="light"]) {
            --gp-bg: #16141f;
            --gp-head: #1c1929;
            --gp-card: #1e1b2b;
            --gp-line: #302b45;
            --gp-accent: #a894ff;
            --gp-chip: #2a2440;
            --gp-body: #d6d1e8;
            --gp-muted: #8f88a8;
            --gp-code-bg: #0e0d15;
            --gp-code-fg: #cfc9e6;
          }
        }
      `}</style>
    </div>
  );
}

// ---------------------------------------------------------------- 部品

const linkStyle: React.CSSProperties = {
  color: "var(--gp-accent)",
  fontWeight: 600,
};

function Card({ children }: { children: React.ReactNode }) {
  return (
    <section
      style={{
        background: "var(--gp-card)",
        border: "1px solid var(--gp-line)",
        borderRadius: 12,
        padding: "12px 14px",
      }}
    >
      {children}
    </section>
  );
}

function Label({ children }: { children: React.ReactNode }) {
  return (
    <div
      style={{
        fontSize: 10,
        fontWeight: 700,
        letterSpacing: ".1em",
        color: "var(--gp-accent)",
      }}
    >
      {children}
    </div>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div
      style={{
        fontSize: 10,
        fontWeight: 700,
        letterSpacing: ".14em",
        color: "var(--gp-muted)",
        marginTop: 2,
      }}
    >
      {children}
    </div>
  );
}

function Badge({ children }: { children: React.ReactNode }) {
  return (
    <span
      style={{
        display: "inline-block",
        background: "var(--gp-accent)",
        color: "#fff",
        borderRadius: 999,
        padding: "3px 10px",
        fontSize: 10,
        fontWeight: 700,
        letterSpacing: ".08em",
      }}
    >
      {children}
    </span>
  );
}

function Tags({ items }: { items: string[] }) {
  return (
    <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 10 }}>
      {items.map((t) => (
        <span
          key={t}
          style={{
            background: "var(--gp-chip)",
            color: "var(--gp-accent)",
            border: "1px solid var(--gp-line)",
            borderRadius: 999,
            padding: "4px 10px",
            fontSize: 11,
            fontWeight: 600,
            whiteSpace: "nowrap",
          }}
        >
          {t}
        </span>
      ))}
    </div>
  );
}

function Bullets({ items }: { items: React.ReactNode[] }) {
  return (
    <ul
      style={{
        margin: "6px 0 0",
        paddingLeft: 18,
        color: "var(--gp-body)",
        lineHeight: 1.85,
      }}
    >
      {items.map((it, i) => (
        <li key={i}>{it}</li>
      ))}
    </ul>
  );
}

function Steps({ items }: { items: React.ReactNode[] }) {
  return (
    <ol
      style={{
        margin: 0,
        paddingLeft: 20,
        color: "var(--gp-body)",
        lineHeight: 1.85,
      }}
    >
      {items.map((it, i) => (
        <li key={i} style={{ marginBottom: i === items.length - 1 ? 0 : 8 }}>
          {it}
        </li>
      ))}
    </ol>
  );
}

function Code({
  children,
  inline,
}: {
  children: React.ReactNode;
  inline?: boolean;
}) {
  if (inline) {
    return (
      <code
        style={{
          background: "var(--gp-chip)",
          color: "var(--gp-accent)",
          borderRadius: 4,
          padding: "1px 5px",
          fontSize: 12,
          fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
        }}
      >
        {children}
      </code>
    );
  }
  return (
    <pre
      style={{
        background: "var(--gp-code-bg)",
        color: "var(--gp-code-fg)",
        borderRadius: 10,
        padding: "12px 14px",
        margin: 0,
        fontSize: 11.5,
        lineHeight: 1.65,
        // ★ 横スクロールはこの中だけ。ページ本体を横に伸ばさない
        overflowX: "auto",
        fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
      }}
    >
      {children}
    </pre>
  );
}
