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
  /**
   * ★ サーバーの項目名は blockedReason。以前ここが reason になっており、
   *   型としては通るが実行時は常に undefined だった。
   *   参照している画面が無かったため誰も気付いていなかっただけで、
   *   使い始めた瞬間に「止められた理由が出ない」形で表面化する。
   */
  blockedReason?: string;
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

/**
 * 顧客。
 *
 * ★ 一覧は主電話番号と担当者を含む。行ごとに追加の問い合わせをしないため、
 *   というより「架電」ボタンを出すのに番号が要るため。
 *   番号の無い顧客にボタンを出しても、押した先で失敗するだけ。
 */
export interface CustomerRow {
  id: string;
  company_name: string | null;
  contact_name: string | null;
  status: string;
  created_at: string;
  owner_id: string | null;
  owner_name: string | null;
  phone_id: string | null;
  phone_e164: string | null;
  phone_raw: string | null;
  do_not_call: boolean;
}

export const customers = {
  list: (q?: string) =>
    api<CustomerRow[]>(
      `/api/v1/customers${q ? `?q=${encodeURIComponent(q)}` : ""}`,
    ),
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

// ---------------------------------------------------------------- 電話設定

export interface TelephonyCheck {
  key: string;
  label: string;
  ok: boolean;
  detail: string;
}

export const telephony = {
  get: () =>
    api<{
      configured: boolean;
      callerId?: string;
      machineDetection?: string;
      recordingEnabled?: boolean;
      dialingEnabled?: boolean;
    }>("/api/v1/admin/telephony"),

  save: (settings: {
    callerId: string;
    machineDetection: string;
    recordingEnabled: boolean;
    dialingEnabled: boolean;
  }) =>
    api<{ ok: boolean; message: string }>("/api/v1/admin/telephony", {
      method: "PUT",
      body: JSON.stringify(settings),
    }),

  /** ★ 設定の保存と分けてある。事故のときに 1 操作で確実に止めるため。 */
  setDialing: (enabled: boolean) =>
    api<{ ok: boolean; message: string }>(
      `/api/v1/admin/telephony/dialing?enabled=${enabled}`,
      { method: "POST" },
    ),

  /** ★ manager でも見られる。止まっている理由を知りたいのは設定者だけではない。 */
  diagnose: () =>
    api<{ canDial: boolean; checks: TelephonyCheck[] }>(
      "/api/v1/admin/telephony/diagnose",
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


// ---------------------------------------------------------------- 分析

function range(from?: string, to?: string): string {
  const params = new URLSearchParams();
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  const q = params.toString();
  return q ? `?${q}` : "";
}

export interface HourlyRow {
  local_hour: number;
  denominator: number;
  connected: number;
  successes: number;
}

export interface WeekdayRow {
  local_weekday: number;
  denominator: number;
  connected: number;
  successes: number;
}

export interface OperatorRow {
  operator_id: string | null;
  operator_name: string;
  operator_status: string | null;
  denominator: number;
  connected: number;
  conversations: number;
  successes: number;
  blocked: number;
  avg_talk_seconds: number;
}

export interface BlockedRow {
  blocked_reason: string;
  count: number;
}

/**
 * ★ manager 以上でないと 403 になる。担当者別の成績は評価に直結するので、
 *   オペレーター同士では見えないようサーバー側で止めている。
 */
export const analytics = {
  hourly: (from?: string, to?: string) =>
    api<HourlyRow[]>(`/api/v1/analytics/hourly${range(from, to)}`),
  weekday: (from?: string, to?: string) =>
    api<WeekdayRow[]>(`/api/v1/analytics/weekday${range(from, to)}`),
  operator: (from?: string, to?: string) =>
    api<OperatorRow[]>(`/api/v1/analytics/operator${range(from, to)}`),
  blocked: (from?: string, to?: string) =>
    api<BlockedRow[]>(`/api/v1/analytics/blocked${range(from, to)}`),
};

// ---------------------------------------------------------------- 架電履歴

export interface HistoryRow {
  id: string;
  started_at: string;
  answered_at: string | null;
  ended_at: string | null;
  duration_seconds: number | null;
  dial_state: string;
  blocked_reason: string | null;
  to_e164: string;
  disposition_code: string | null;
  disposition_label: string | null;
  disposition_is_success: boolean | null;
  customer_id: string;
  company_name: string | null;
  operator_id: string | null;
  operator_name: string | null;
  recording_id: string | null;
}

export interface HistoryPage {
  rows: HistoryRow[];
  total: number;
  offset: number;
  limit: number;
  /** ★ オペレーターは自分の通話だけ。黙って絞ると件数の問い合わせになる */
  scopedToSelf: boolean;
}

export const history = {
  list: (params: {
    from?: string;
    to?: string;
    kind?: string;
    q?: string;
    operatorId?: string;
    offset?: number;
    limit?: number;
  }) => {
    const search = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== "") search.set(k, String(v));
    });
    const q = search.toString();
    return api<HistoryPage>(`/api/v1/call-history${q ? `?${q}` : ""}`);
  },

  detail: (id: string) =>
    api<{
      call: Record<string, unknown>;
      dispositions: Array<Record<string, unknown>>;
      events: Array<Record<string, unknown>>;
    }>(`/api/v1/call-history/${id}`),
};

// ---------------------------------------------------------------- 録音

/**
 * 録音の再生 URL。
 *
 * ★ voice 側にしかない。S3 の資格情報を持つのが voice だけだから。
 *   api は録音の「有無」だけを返す。
 *
 * ★ URL は 5 分で切れる。取得のたびに recording_access_logs に
 *   記録が残るので、押した回数だけ記録される（これは意図した動作）。
 */
export const recordings = {
  playbackUrl: (recordingId: string) =>
    voice<{
      url: string;
      expiresInSeconds: number;
      contentType: string;
      durationSeconds: number | null;
    }>(`/recordings/${recordingId}/url`),
};

// ---------------------------------------------------------------- 利用者

export interface UserRow {
  id: string;
  email: string;
  display_name: string;
  role: Role;
  status: "active" | "disabled";
  last_seen_at: string | null;
  created_at: string;
  password_change_required: boolean;
  call_count: number;
}

export const users = {
  list: () => api<UserRow[]>("/api/v1/admin/users"),

  /** ★ initialPassword はこの応答にしか現れない。保存も再表示もできない */
  create: (email: string, displayName: string, role: Role) =>
    api<{
      id: string;
      email: string;
      role: Role;
      initialPassword: string;
      message: string;
    }>("/api/v1/admin/users", {
      method: "POST",
      body: JSON.stringify({ email, displayName, role }),
    }),

  changeRole: (id: string, role: Role) =>
    api<{ ok: boolean; message: string }>(`/api/v1/admin/users/${id}/role`, {
      method: "PATCH",
      body: JSON.stringify({ role }),
    }),

  setStatus: (id: string, active: boolean) =>
    api<{ ok: boolean; message: string }>(
      `/api/v1/admin/users/${id}/status?active=${active}`,
      { method: "PATCH" },
    ),

  resetPassword: (id: string) =>
    api<{ initialPassword: string; message: string }>(
      `/api/v1/admin/users/${id}/password-reset`,
      { method: "POST" },
    ),

  changeOwnPassword: (currentPassword: string, newPassword: string) =>
    api<{ ok: boolean; message: string }>("/api/v1/auth/password", {
      method: "POST",
      body: JSON.stringify({ currentPassword, newPassword }),
    }),
};

// ---------------------------------------------------------------- 権限

export interface CapabilityRow {
  key: string;
  label: string;
  detail: string;
  operator: boolean;
  manager: boolean;
  admin: boolean;
  allowedForMe: boolean;
}

/**
 * ★ この表はサーバーの PermissionCatalog を映しているだけで、
 *   実際に権限を決めているのは SecurityConfig と @PreAuthorize。
 *   両者がずれていないことは PermissionMatrixTest が実際に叩いて
 *   確かめている（ずれるとテストが落ちる）。
 */
export const permissions = {
  get: () =>
    api<{ myRole: Role; capabilities: CapabilityRow[] }>("/api/v1/permissions"),
};
