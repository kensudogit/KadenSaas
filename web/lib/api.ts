/**
 * バックエンド 2 本への呼び出しをまとめる。
 *
 * ★ このプロジェクトは api（Spring Boot／業務）と voice（FastAPI／電話）の
 *   2 つに分かれている。画面側がその分割を意識しないで済むよう、
 *   呼び先の判断をここに閉じ込める。
 *
 * ★ トークンは localStorage に置く。Cookie にしないのは、
 *   API が別オリジンで、SameSite の扱いが環境ごとに変わるため。
 *   その代わり XSS には弱いので、画面側で dangerouslySetInnerHTML を
 *   使わないことが前提になる。
 *
 * ★ tenantId をリクエストに載せない。テナントは JWT の中の値だけで決まる。
 *   クエリやヘッダで指定できるようにすると、その瞬間に分離が壊れる。
 */

const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const VOICE_BASE =
  process.env.NEXT_PUBLIC_VOICE_BASE_URL ?? "http://localhost:8001";

const TOKEN_KEY = "kaden.token";

export type Role = "operator" | "manager" | "admin";

export interface Session {
  token: string;
  user: { id: string; displayName: string; role: Role };
  tenant: { id: string; name: string; timezone: string };
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: unknown,
    message: string,
  ) {
    super(message);
  }
}

// ---------------------------------------------------------------- トークン

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  window.localStorage.setItem(TOKEN_KEY, token);
}

const ROLE_KEY = "kaden.role";

export function setRole(role: Role): void {
  window.localStorage.setItem(ROLE_KEY, role);
}

export function getRole(): Role | null {
  if (typeof window === "undefined") return null;
  return (window.localStorage.getItem(ROLE_KEY) as Role) ?? null;
}

export function clearToken(): void {
  window.localStorage.removeItem(TOKEN_KEY);
  window.localStorage.removeItem("kaden.role");
}

// ---------------------------------------------------------------- 共通

async function request<T>(
  base: string,
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...((init.headers as Record<string, string>) ?? {}),
  };
  if (token) headers.Authorization = `Bearer ${token}`;

  const response = await fetch(`${base}${path}`, { ...init, headers });

  if (response.status === 401) {
    // ★ 期限切れは静かにログイン画面へ戻す。エラーを出しても
    //   利用者にできることが無い
    clearToken();
    if (typeof window !== "undefined") window.location.href = "/";
    throw new ApiError(401, null, "認証が切れました");
  }

  const text = await response.text();
  const body = text ? JSON.parse(text) : null;

  if (!response.ok) {
    const message =
      (body && (body.message || body.error)) ?? `エラー (${response.status})`;
    throw new ApiError(response.status, body, message);
  }
  return body as T;
}

const api = <T>(path: string, init?: RequestInit) =>
  request<T>(API_BASE, path, init);
const voice = <T>(path: string, init?: RequestInit) =>
  request<T>(VOICE_BASE, path, init);

// ---------------------------------------------------------------- 認証

export async function login(
  tenantSlug: string,
  email: string,
  password: string,
): Promise<Session> {
  const session = await api<Session>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify({ tenantSlug, email, password }),
  });
  setToken(session.token);
  // ★ 画面の出し分けにだけ使う。権限の判定はサーバー側が行う。
  //   ここを書き換えても admin の API は 403 になる
  setRole(session.user.role);
  return session;
}

// ---------------------------------------------------------------- 架電

export interface QueueItem {
  available: boolean;
  target?: {
    target_id: string;
    customer_id: string;
    phone_id: string;
    company_name: string | null;
    contact_name: string | null;
    note: string | null;
    raw_number: string;
    e164: string;
    attempts: number;
    last_attempt_at: string | null;
    is_dnc: boolean;
  };
  history?: Array<{
    started_at: string;
    dial_state: string;
    disposition_code: string | null;
    disposition_label: string | null;
    duration_seconds: number | null;
  }>;
}

export const queue = {
  next: (campaignId: string) =>
    api<QueueItem>(`/api/v1/queue/next?campaignId=${campaignId}`, {
      method: "POST",
    }),
  release: (targetId: string) =>
    api<{ ok: boolean }>(`/api/v1/queue/${targetId}/release`, {
      method: "POST",
    }),
};

/**
 * 発信の結果。
 *
 * ★ accepted=false はエラーではない。関門が正しく止めた結果なので、
 *   画面は reason をそのまま表示すればよい。赤いエラー表示にしない。
 */
export interface DialResult {
  accepted: boolean;
  callSessionId?: string;
  reason?: string;
  message?: string;
}

export const calls = {
  dial: (phoneId: string, campaignId?: string, callTargetId?: string) =>
    api<DialResult>("/api/v1/calls", {
      method: "POST",
      body: JSON.stringify({ phoneId, campaignId, callTargetId }),
    }),

  // ★ 実際に鳴らすのは voice。api が作った queued の行を渡す
  start: (callSessionId: string) =>
    voice<{ ok: boolean; callSid: string }>(
      `/internal/calls/${callSessionId}/dial`,
      { method: "POST" },
    ),

  disposition: (callSessionId: string, code: string, note?: string) =>
    api<{ ok: boolean }>(`/api/v1/calls/${callSessionId}/disposition`, {
      method: "POST",
      body: JSON.stringify({ code, note }),
    }),

  dispositionCodes: () =>
    api<
      Array<{
        code: string;
        label: string;
        isDnc: boolean;
        isConnected: boolean;
      }>
    >("/api/v1/calls/dispositions"),
};

// ---------------------------------------------------------------- KPI

export interface KpiRow {
  local_date: string;
  attempts_total: number;
  denominator: number;
  connected: number;
  conversations: number;
  successes: number;
  blocked: number;
  talk_seconds: number;
  avg_talk_seconds: number;
}

export const kpi = {
  summary: () => api<KpiRow[]>("/api/v1/kpi/summary"),
  hourly: () =>
    api<Array<{ local_hour: number; denominator: number; connected: number }>>(
      "/api/v1/kpi/hourly",
    ),
  blocked: () =>
    api<Array<{ blocked_reason: string; count: number }>>("/api/v1/kpi/blocked"),
};

// ---------------------------------------------------------------- 顧客

export const customers = {
  list: (q?: string) =>
    api<
      Array<{
        id: string;
        companyName: string | null;
        contactName: string | null;
        status: string;
      }>
    >(`/api/v1/customers${q ? `?q=${encodeURIComponent(q)}` : ""}`),
};

/**
 * 管理者用。
 *
 * ★ サンプルデータは実在しない電話番号（03-1234-5xxx）で作られる。
 *   デモデータから本当に発信してしまう事故を防ぐため。
 */
export const admin = {
  generateSampleData: (force = false) =>
    api<{
      customers: number;
      campaigns: number;
      callTargets: number;
      callSessions: number;
      dncEntries: number;
      callbacks: number;
      message: string;
    }>(`/api/v1/admin/sample-data?force=${force}`, { method: "POST" }),

  clearData: () =>
    api<{ ok: boolean; message: string }>(
      "/api/v1/admin/sample-data?confirm=true",
      { method: "DELETE" },
    ),
};

export const dnc = {
  register: (phone: string, reason: string) =>
    api<{ ok: boolean; e164: string }>("/api/v1/dnc", {
      method: "POST",
      body: JSON.stringify({ phone, reason }),
    }),
  check: (phone: string) =>
    api<{ e164: string; blocked: boolean }>(
      `/api/v1/dnc/check?phone=${encodeURIComponent(phone)}`,
    ),
};
