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
import BrandLogo from "@/components/BrandLogo";

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
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <BrandLogo size={28} />
              <Label>KADEN SAAS · 架電特化型SaaS</Label>
            </div>
            <h3 style={{ margin: "6px 0 6px", fontSize: 17 }}>
              発信から KPI までを一本の線で扱う
            </h3>
            <p style={{ margin: 0, color: "var(--gp-body)", lineHeight: 1.75 }}>
              顧客リスト → 発信（人 / AI）→ 通話 → 録音 → 文字起こし →
              AI 要約 → 架電結果 → 再架電 → KPI。テナント分離は PostgreSQL の RLS。
            </p>
            <Tags
              items={[
                "Next.js · React",
                "Spring Boot · 業務API",
                "FastAPI · 音声/AI",
                "PostgreSQL · RLS",
                "Twilio · Media Streams",
                "ASR · LLM · TTS",
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
                  <b>voice-media</b> — Media Streams（双方向）。
                  AI 架電の TTS 返送もここ。<b>必ず別サービス</b>
                </>,
                <>
                  <b>voice-jobs</b> — 録音・文字起こし後の AI 分析（要約）。
                  通話中の LLM 応答とは別経路
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
                  ├─ voice-media   /media  (WebSocket 双方向)
                  ├─ voice-jobs    録音 / 事後 AI 分析
                  └─ S3 · Redis · Twilio · ASR · LLM · TTS`}</Code>

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
                  <a href="http://localhost:3003" style={linkStyle}>
                    localhost:3003
                  </a>
                  （compose の web。3002 は他プロジェクトと衝突しやすいので 3003 固定）でログイン。
                  テナント <Code inline>demo</Code> /
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
                  <Code inline>kaden_app</Code> /{" "}
                  <Code inline>kaden_migrator</Code> を作る。
                  <Code inline>DATABASE_URL</Code> は app、
                  <Code inline>DATABASE_MIGRATOR_URL</Code> は migrator（Flyway 必須）。
                  <b>superuser のままだと RLS が効きません</b>
                </>,
                <>
                  <b>オートダイヤラーで DB エラー</b> —{" "}
                  <Code inline>db/RAILWAY-FLYWAY.md</Code>。
                  manager 以上は{" "}
                  <Code inline>/api/v1/admin/schema/diagnose</Code>
                </>,
                <>
                  <Code inline>CORS_ORIGIN</Code>（<b>api</b>）に web の公開 URL。
                  例: <Code inline>https://web-production-….up.railway.app</Code>。
                  未設定だと localhost のみ許可され、本番は Failed to fetch になる
                </>,
                <>
                  <Code inline>JWT_SECRET</Code> は
                  <Code inline>{"${{KadenSaas.JWT_SECRET}}"}</Code> の参照で繋ぐ。
                  値がずれると「api では通るのに voice で 401」になります
                </>,
                <>
                  <b>AI 架電</b> — voice に{" "}
                  <Code inline>PUBLIC_WSS_URL</Code>（Media Stream）、
                  <Code inline>ASR_PROVIDER</Code> / <Code inline>ASR_API_KEY</Code>、
                  <Code inline>LLM_API_KEY</Code>、任意で{" "}
                  <Code inline>TTS_PROVIDER=openai</Code> と{" "}
                  <Code inline>TTS_API_KEY</Code>。TTS 未設定時は文字起こしのみ
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
                  <b>サンプルを投入</b> — ダッシュボードまたは顧客リストの
                  「サンプルデータを投入」（<b>管理者のみ</b>）。
                  架電用データに加え、コールセンター用 FAQ 5 件と受付履歴 3 件も入ります。
                  電話番号は実在しない 03-1234-5xxx なので誤発信しません
                </>,
                <>
                  <b>人が架電</b> —{" "}
                  <a href="/operator" style={linkStyle}>
                    架電
                  </a>
                  でキャンペーン ID を入れて「次の 1 件を受け取る」
                </>,
                <>
                  <b>AI 自動架電</b> —{" "}
                  <a href="/ai-outbound" style={linkStyle}>
                    AI 架電
                  </a>
                  （03-1234-52xx）
                </>,
                <>
                  <b>オートダイヤラー</b> —{" "}
                  <a href="/auto-dialer" style={linkStyle}>
                    オートダイヤル
                  </a>
                  でキューを順次発信（03-1234-53xx）。api に{" "}
                  <Code inline>KADEN_VOICE_BASE_URL</Code> が必要
                </>,
                <>
                  <b>IVR 自動架電</b> —{" "}
                  <a href="/ivr-outbound" style={linkStyle}>
                    IVR 架電
                  </a>
                  （Gather メニュー 1/2/9、03-1234-54xx）
                </>,
                <>
                  <b>コールセンターを確認</b> — ナビの
                  <a href="/call-center" style={linkStyle}>
                    コールセンター
                  </a>
                  タブ。FAQ と受付履歴が空なら、同画面の
                  「コールセンター用サンプルを投入」（manager / admin）でデモデータだけ足せます
                </>,
              ]}
            />
          </Card>

          <SectionLabel>AI 自動架電</SectionLabel>
          <Card>
            <Steps
              items={[
                <>
                  <b>発信の関門は人架電と同じ。</b> api の DialingGate → queued →
                  voice が Twilio 発信。
                  <Code inline>conversation_mode=ai_agent</Code> の通話だけ
                  voice が相手発話 → LLM → TTS のループを回します
                </>,
                <>
                  <b>1. サンプル</b> — 画面を開くと{" "}
                  <Code inline>POST /api/v1/ai-outbound/seed-demo</Code>{" "}
                  相当が空テナントで走り、キャンペーン「サンプル: AI
                  自動架電」と 5 件の架電先が載ります（オペレーター可）。
                  「サンプルを再登録」で冪等に再実行
                </>,
                <>
                  <b>2. 試す</b> — 一覧で DNC 行（関門デモ）を確認し、
                  pending の相手で「次の 1 件を取る」→「AI で発信する」
                </>,
                <>
                  <b>3. 本番データ</b> — キャンペーンに{" "}
                  <Code inline>script</Code>（会話の指示）を書き、
                  通常どおり call_targets を積む。管理者の一括サンプル投入でも{" "}
                  <Code inline>ai_agent_enabled</Code> 付きキャンペーンが 1 件入ります
                </>,
                <>
                  <b>番号帯</b> — AI デモ専用は 03-1234-52xx、従来サンプルは
                  03-1234-5xxx。いずれも架空です
                </>,
              ]}
            />
          </Card>

          <SectionLabel>オートダイヤラー</SectionLabel>
          <Card>
            <Steps
              items={[
                <>
                  <b>プログレッシブ（1 本ずつ）。</b> 通話が終わるまで次をかけない。
                  <Code inline>auto_dialer_runs</Code> の tick が reserve → DialingGate →
                  queued → voice dial まで一気通貫
                </>,
                <>
                  <b>サンプル</b> —{" "}
                  <Code inline>/api/v1/auto-dialer/seed-demo</Code> または画面の自動登録。
                  キャンペーン「サンプル: オートダイヤラー」
                </>,
                <>
                  <b>会話モード</b> — 開始時に <Code inline>operator</Code> /{" "}
                  <Code inline>ai_agent</Code> / <Code inline>ivr</Code> を選べます。
                  IVR モードがデモ向き
                </>,
                <>
                  <b>必須設定</b> — api の{" "}
                  <Code inline>KADEN_VOICE_BASE_URL</Code>（Railway では voice-web の URL）。
                  未設定だと tick が voice 発信で失敗します
                </>,
              ]}
            />
          </Card>

          <SectionLabel>IVR 自動架電</SectionLabel>
          <Card>
            <Steps
              items={[
                <>
                  <b>応答後</b> — voice の <Code inline>/twilio/voice</Code> が{" "}
                  <Code inline>conversation_mode=ivr</Code> を見て Gather TwiML を返します。
                  相手の 1 桁入力は <Code inline>/twilio/ivr/gather</Code>
                </>,
                <>
                  <b>メニュー</b> — キャンペーンの{" "}
                  <Code inline>ivr_welcome_text</Code> と{" "}
                  <Code inline>ivr_menu_json</Code>（1=資料、2=担当、9=終了）
                </>,
                <>
                  <b>サンプル</b> — IVR 架電画面で自動登録（03-1234-54xx）。
                  オートダイヤラーから IVR モードで連続発信も可能
                </>,
              ]}
            />
          </Card>

          <SectionLabel>コールセンター（着信オペレーター支援）</SectionLabel>
          <Card>
            <Steps
              items={[
                <>
                  <b>ここから発信しない。</b> 架電画面・DialingGate とは別経路です。
                  音声認識精度も RAG 精度も未計測。音声は学習に使いません
                </>,
                <>
                  <b>1. サンプルを入れる</b> — FAQ が無いテナントでは
                  「回答案を作る」だけでデモ FAQ が自動投入されます。
                  受付履歴まで見る場合は「コールセンター用サンプルを投入」
                  （オペレーターでも可）
                </>,
                <>
                  <b>2. 問い合わせを試す</b> — 例:「請求書が届きません。再発行できますか。」
                  →「回答案を作る」。意図 <Code inline>billing</Code>、出典にチャンク ID。
                  <Code inline>OPENAI_API_KEY</Code> があるときは OpenAI が FAQ 範囲内で
                  オペレーター向け回答案を書きます（未設定時はテンプレート）
                </>,
                <>
                  <b>3. 該当なし</b> — ナレッジに無い文言は「出典の無い案内は作りません」と返します。
                  生成だけで FAQ を捏造しません
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
                <>
                  <b>コールセンターの FAQ が空</b> —
                  テナントにナレッジが無いか、RLS で 0 行になっています。
                  管理者で「コールセンター用サンプルを投入」するか、
                  <Code inline>scripts/seed-dev.sql</Code> を流してください
                </>,
                <>
                  <b>AI 架電が無音 / 応答しない</b> — Twilio・
                  <Code inline>PUBLIC_WSS_URL</Code>、
                  <Code inline>ASR_API_KEY</Code>、
                  <Code inline>LLM_API_KEY</Code> を確認。TTS 無しでも
                  文字起こしに AI 発話は残ります
                </>,
                <>
                  <b>AI 架電の一覧が空</b> — api が未デプロイ（V17 以降）か
                  401。ブラウザのネットワークで{" "}
                  <Code inline>/api/v1/ai-outbound/overview</Code> を確認
                </>,
                <>
                  <b>オートダイヤラーが voice で失敗</b> —{" "}
                  <Code inline>KADEN_VOICE_BASE_URL</Code> と JWT_SECRET の一致、
                  電話設定の診断を確認
                </>,
                <>
                  <b>IVR でメニューが流れない</b> — 通話の{" "}
                  <Code inline>conversation_mode</Code> が ivr か、
                  キャンペーンで <Code inline>ivr_enabled</Code> とメニュー JSON があるか
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
