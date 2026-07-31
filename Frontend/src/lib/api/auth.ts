import { AUTH_BASE_URL } from "@/constants";
import { requestData, requestJson } from "@/lib/api/http";

// Re-export để chỗ gọi không phải import từ hai file. Định nghĩa nằm ở http.ts
// vì cách phân loại lỗi này dùng chung cho mọi module API, không riêng auth.
export {
  ApiRejectedError,
  ApiUnavailableError,
  COLD_START_RETRY_DELAYS_MS,
  withRetry,
  type ApiEnvelope,
} from "@/lib/api/http";

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface AuthUser {
  id: number;
  email: string;
  name: string;
  avatar: string | null;
  provider: string;
  roles: string[];
}

const url = (endpoint: string) => `${AUTH_BASE_URL}${endpoint}`;

function jsonPost(body: Record<string, unknown>, accessToken?: string): RequestInit {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (accessToken) headers["Authorization"] = `Bearer ${accessToken}`;
  return { method: "POST", headers, body: JSON.stringify(body) };
}

export async function apiLogin(
  email: string,
  password: string,
): Promise<AuthTokens> {
  return requestData<AuthTokens>(url("/auth/login"), jsonPost({ email, password }));
}

/** Returns the confirmation message — user must verify email before logging in */
export async function apiRegister(
  email: string,
  password: string,
  name: string,
): Promise<string> {
  const envelope = await requestJson<null>(
    url("/auth/register"),
    jsonPost({ email, password, name }),
  );
  return envelope.message;
}

export async function apiRefreshToken(
  refreshToken: string,
): Promise<AuthTokens> {
  return requestData<AuthTokens>(url("/auth/refresh"), jsonPost({ refreshToken }));
}

/**
 * Đổi mã dùng-một-lần từ luồng đăng nhập Google lấy token.
 *
 * Mã sống 60 giây và chỉ đổi được một lần, nên phải gọi ngay khi trang callback load.
 */
export async function apiExchangeOAuthCode(code: string): Promise<AuthTokens> {
  return requestData<AuthTokens>(
    url("/auth/oauth2/exchange"),
    jsonPost({ code }),
  );
}

export async function apiGetMe(accessToken: string): Promise<AuthUser> {
  // Không tự phát auth:unauthorized ở đây nữa: một 401 lúc backend đang khởi
  // động không chứng minh được token sai. Quyền quyết định đăng xuất thuộc
  // AuthContext, nơi phân biệt được ApiRejectedError với ApiUnavailableError.
  return requestData<AuthUser>(url("/auth/me"), {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}

export async function apiLogout(accessToken?: string): Promise<void> {
  try {
    await fetch(url("/auth/logout"), {
      method: "POST",
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    });
  } catch {
    // ignore — tokens will be cleared client-side regardless
  }
}

export async function apiForgotPassword(email: string): Promise<void> {
  await requestJson<null>(url("/auth/forgot-password"), jsonPost({ email }));
}

export async function apiResetPassword(
  token: string,
  newPassword: string,
): Promise<void> {
  await requestJson<null>(
    url("/auth/reset-password"),
    jsonPost({ token, newPassword }),
  );
}

export async function apiVerifyEmail(token: string): Promise<void> {
  await requestJson<null>(url(`/auth/verify-email?token=${encodeURIComponent(token)}`));
}
