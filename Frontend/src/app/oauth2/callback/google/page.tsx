"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/contexts/AuthContext";
import {
  apiExchangeOAuthCode,
  COLD_START_RETRY_DELAYS_MS,
  withRetry,
  type AuthTokens,
} from "@/lib/api/auth";

/**
 * Dự phòng khi token đến qua URL mà không kèm `expiresIn` (backend bản cũ).
 * Khớp với app.jwt.access-expiration = 1800000ms.
 */
const FALLBACK_ACCESS_LIFETIME_MS = 30 * 60 * 1000;

/**
 * Đọc token theo kiểu cũ: backend gắn thẳng accessToken/refreshToken vào query string.
 *
 * Giữ lại vì Frontend (Vercel) và Backend (Render) deploy độc lập — trong khoảng thời
 * gian hai bên lệch phiên bản, frontend mới vẫn phải hiểu được backend cũ, nếu không
 * đăng nhập Google sẽ gãy hoàn toàn. Gỡ được sau khi cả hai đã chạy ổn định.
 */
function readLegacyTokensFromUrl(params: URLSearchParams): AuthTokens | null {
  const accessToken = params.get("accessToken");
  const refreshToken = params.get("refreshToken");
  if (!accessToken || !refreshToken) return null;

  const expiresIn = Number(params.get("expiresIn"));
  return {
    accessToken,
    refreshToken,
    tokenType: "Bearer",
    expiresIn:
      Number.isFinite(expiresIn) && expiresIn > 0
        ? expiresIn
        : FALLBACK_ACCESS_LIFETIME_MS,
  };
}

export default function GoogleCallbackPage() {
  const router = useRouter();
  const { hydrateFromTokens } = useAuth();
  const [message, setMessage] = useState("Đang xử lý đăng nhập...");

  // React StrictMode gọi effect hai lần ở dev. Mã handoff chỉ đổi được một lần nên
  // lượt thứ hai chắc chắn thất bại — phải chặn, không thì dev nào cũng thấy lỗi giả.
  const handled = useRef(false);

  useEffect(() => {
    if (handled.current) return;
    handled.current = true;

    const params = new URLSearchParams(window.location.search);
    const error = params.get("error");

    if (error) {
      router.replace(`/login?error=${encodeURIComponent(error)}`);
      return;
    }

    const failed = (reason: string) =>
      router.replace(`/login?error=${encodeURIComponent(reason)}`);

    async function completeLogin() {
      const code = params.get("code");
      let tokens: AuthTokens;

      if (code) {
        // Backend không gửi token qua URL nữa: đổi mã lấy token qua POST.
        // withRetry vì backend có thể đang ngủ dậy — nhưng mã chỉ sống 60 giây,
        // hết hạn thì server từ chối và withRetry không thử lại (ApiRejectedError).
        tokens = await withRetry(() => apiExchangeOAuthCode(code), {
          delaysMs: COLD_START_RETRY_DELAYS_MS,
        });
      } else {
        const legacy = readLegacyTokensFromUrl(params);
        if (!legacy) throw new Error("Đăng nhập Google không trả về mã hợp lệ");
        tokens = legacy;
      }

      // Phải qua context: AuthProvider ở root layout không mount lại khi điều hướng
      // client-side, ghi thẳng localStorage thì header vẫn hiện nút "Đăng nhập".
      await hydrateFromTokens(tokens);
    }

    completeLogin()
      .then(() => {
        // replace chứ không push: xoá URL chứa mã khỏi lịch sử duyệt web.
        router.replace("/");
      })
      .catch((err: unknown) => {
        setMessage("Đăng nhập thất bại, đang chuyển về trang đăng nhập...");
        failed(
          err instanceof Error && err.message
            ? err.message
            : "Không hoàn tất được đăng nhập Google, vui lòng thử lại",
        );
      });
  }, [router, hydrateFromTokens]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#050918] text-white">
      <p className="text-sm text-[#8f99bb]">{message}</p>
    </div>
  );
}
