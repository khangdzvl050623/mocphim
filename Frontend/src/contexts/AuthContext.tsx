"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import {
  apiGetMe,
  apiLogin,
  apiLogout,
  apiRefreshToken,
  apiRegister,
  ApiRejectedError,
  COLD_START_RETRY_DELAYS_MS,
  withRetry,
  type AuthTokens,
  type AuthUser,
} from "@/lib/api/auth";

const ACCESS_TOKEN_KEY = "accessToken";
const REFRESH_TOKEN_KEY = "refreshToken";
const TOKEN_EXPIRY_KEY = "tokenExpiry";

/* ------------------------------------------------------------------ *
 * Tunable — sửa ở đây, không rải magic number trong logic bên dưới.
 * ------------------------------------------------------------------ */

/**
 * Đơn vị của `expiresIn` trong response token.
 * Backend hiện trả millisecond (app.jwt.access-expiration=1800000). Chuẩn OAuth2
 * lại quy định second, nên khi đổi backend chỉ cần đổi hằng này.
 */
const EXPIRES_IN_UNIT: "ms" | "s" = "ms";

/** Refresh sớm hơn thời điểm hết hạn để tránh đua với độ trễ đường truyền. */
const TOKEN_EXPIRY_BUFFER_MS = 30_000;

/** Chu kỳ kiểm tra tài khoản còn hiệu lực. Bỏ qua khi tab ẩn. */
const ACCOUNT_CHECK_INTERVAL_MS = 60_000;

interface AuthContextValue {
  user: AuthUser | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<AuthUser>;
  /** Trả về message xác nhận gửi email — không tự đăng nhập */
  register: (email: string, password: string, name: string) => Promise<string>;
  /**
   * Nạp phiên từ token có sẵn, dùng cho luồng OAuth2: token về qua query string
   * của trang callback chứ không qua apiLogin.
   *
   * Bắt buộc phải đi qua đây thay vì tự ghi localStorage rồi điều hướng: provider
   * này nằm ở root layout nên điều hướng client-side KHÔNG làm nó mount lại, state
   * `user` sẽ vẫn là null và giao diện hiện như chưa đăng nhập cho tới khi F5.
   */
  hydrateFromTokens: (tokens: AuthTokens) => Promise<AuthUser>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function saveTokens(tokens: AuthTokens) {
  localStorage.setItem(ACCESS_TOKEN_KEY, tokens.accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken);
  const lifetimeMs =
    EXPIRES_IN_UNIT === "ms" ? tokens.expiresIn : tokens.expiresIn * 1_000;
  const expiry = Date.now() + lifetimeMs - TOKEN_EXPIRY_BUFFER_MS;
  localStorage.setItem(TOKEN_EXPIRY_KEY, String(expiry));
}

function clearTokens() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRY_KEY);
}

function isAccessTokenValid(): boolean {
  const expiry = localStorage.getItem(TOKEN_EXPIRY_KEY);
  if (!expiry) return false;
  const parsed = parseInt(expiry, 10);
  if (Number.isNaN(parsed)) return false;
  return Date.now() < parsed;
}

/**
 * Chỉ cho một request refresh chạy tại một thời điểm. Nhiều call cùng phát hiện
 * token hết hạn sẽ chia sẻ chung một promise, tránh việc refresh song song.
 */
let refreshInFlight: Promise<AuthTokens> | null = null;

function refreshOnce(refreshToken: string): Promise<AuthTokens> {
  if (!refreshInFlight) {
    refreshInFlight = apiRefreshToken(refreshToken).finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

/**
 * Nguồn duy nhất để lấy access token còn hạn: tự refresh khi cần.
 *
 * Trả null khi không còn gì để dùng (chưa đăng nhập hoặc mất refresh token).
 * Ném ApiRejectedError nếu server từ chối refresh token, ApiUnavailableError nếu
 * không gọi được server — hai trường hợp này phải được xử lý khác nhau.
 *
 * Export để các module API khác dùng chung thay vì tự đọc localStorage rồi gửi
 * token đã hết hạn.
 */
export async function getValidAccessToken(): Promise<string | null> {
  const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY);
  if (accessToken && isAccessTokenValid()) return accessToken;

  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
  if (!refreshToken) return null;

  const tokens = await refreshOnce(refreshToken);
  saveTokens(tokens);
  return tokens.accessToken;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      const hasSomething =
        localStorage.getItem(ACCESS_TOKEN_KEY) ||
        localStorage.getItem(REFRESH_TOKEN_KEY);
      if (!hasSomething) {
        setIsLoading(false);
        return;
      }

      try {
        // Server có thể chưa sẵn sàng (khởi động lại, deploy, scale from zero):
        // chờ theo backoff thay vì kết luận token sai. withRetry chỉ thử lại với
        // ApiUnavailableError, còn ApiRejectedError được ném ra ngay.
        const me = await withRetry(
          async () => {
            const token = await getValidAccessToken();
            if (!token) return null;
            return apiGetMe(token);
          },
          {
            delaysMs: COLD_START_RETRY_DELAYS_MS,
            shouldAbort: () => cancelled,
          },
        );

        if (cancelled) return;
        if (me) setUser(me);
        else clearTokens(); // không còn token nào dùng được
      } catch (err) {
        // Chỉ xoá session khi server thật sự từ chối. Với lỗi tạm thời, giữ
        // nguyên token: refresh token còn hạn nhiều ngày, lần vào sau dùng lại
        // được, không cần bắt người dùng đăng nhập lại.
        if (err instanceof ApiRejectedError) clearTokens();
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }

    restoreSession();
    return () => {
      cancelled = true;
    };
  }, []);

  // Cửa để module khác buộc đăng xuất. Hợp đồng: CHỈ phát khi server thật sự từ
  // chối (ApiRejectedError), tuyệt đối không phát khi gặp lỗi mạng hay 5xx —
  // nếu không sẽ lặp lại đúng bug cũ là mất session vì một sự cố tạm thời.
  // Hiện chưa module nào phát; auth.ts đã bỏ dispatch trên mọi 401.
  useEffect(() => {
    function handleUnauthorized() {
      clearTokens();
      setUser(null);
    }
    window.addEventListener("auth:unauthorized", handleUnauthorized);
    return () => window.removeEventListener("auth:unauthorized", handleUnauthorized);
  }, []);

  // Định kỳ kiểm tra tài khoản còn hiệu lực (bị khoá / bị xoá thì đăng xuất).
  useEffect(() => {
    if (!user) return;
    const interval = setInterval(async () => {
      // Tab ẩn thì không cần kiểm tra — người dùng không tương tác.
      if (typeof document !== "undefined" && document.hidden) return;
      try {
        // Qua getValidAccessToken để tự refresh khi access token đã hết hạn.
        // Trước đây chỗ này gửi thẳng token trong localStorage, nên sau khi token
        // hết hạn là nhận 401 rồi đăng xuất, dù refresh token vẫn còn hạn.
        const token = await getValidAccessToken();
        if (!token) {
          clearTokens();
          setUser(null);
          return;
        }
        await apiGetMe(token);
      } catch (err) {
        // Mất mạng hay server đang lỗi thì bỏ qua, chu kỳ sau kiểm tra lại.
        if (err instanceof ApiRejectedError) {
          clearTokens();
          setUser(null);
        }
      }
    }, ACCOUNT_CHECK_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [user]);

  const login = useCallback(
    async (email: string, password: string): Promise<AuthUser> => {
      const tokens = await apiLogin(email, password);
      saveTokens(tokens);
      const me = await apiGetMe(tokens.accessToken);
      setUser(me);
      return me;
    },
    [],
  );

  const register = useCallback(
    (email: string, password: string, name: string): Promise<string> =>
      apiRegister(email, password, name),
    [],
  );

  const hydrateFromTokens = useCallback(
    async (tokens: AuthTokens): Promise<AuthUser> => {
      saveTokens(tokens);
      // Cũng chịu cold start: người dùng vừa qua Google về, backend có thể đang ngủ dậy.
      const me = await withRetry(() => apiGetMe(tokens.accessToken), {
        delaysMs: COLD_START_RETRY_DELAYS_MS,
      });
      setUser(me);
      return me;
    },
    [],
  );

  const logout = useCallback(() => {
    const token = localStorage.getItem(ACCESS_TOKEN_KEY) ?? undefined;
    void apiLogout(token);
    clearTokens();
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider
      value={{ user, isLoading, login, register, hydrateFromTokens, logout }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth phải được dùng trong AuthProvider");
  return ctx;
}
