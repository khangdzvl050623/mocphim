"use client";

import { useState } from "react";
import { useAuth } from "@/contexts/AuthContext";
import { apiResendVerification } from "@/lib/api/auth";

/**
 * Nhắc người dùng chưa xác thực email, kèm nút gửi lại.
 *
 * Cần thiết vì đăng nhập KHÔNG kiểm tra isVerified (xem CustomUserDetails.isEnabled,
 * chỉ đọc cột `enabled`). Người đăng ký xong không bấm link vẫn dùng bình thường,
 * nên không có gì cho họ biết tài khoản đang ở trạng thái chưa xác thực.
 */
export default function VerifyEmailBanner() {
  const { user } = useAuth();
  const [state, setState] = useState<"idle" | "sending" | "sent" | "error">("idle");
  const [message, setMessage] = useState("");
  const [dismissed, setDismissed] = useState(false);

  // Tài khoản Google luôn được đánh dấu đã xác thực nên không bao giờ rơi vào đây.
  if (!user || user.isVerified || dismissed) return null;

  const handleResend = async () => {
    setState("sending");
    try {
      const serverMessage = await apiResendVerification(user.email);
      setState("sent");
      setMessage(serverMessage);
    } catch (err) {
      setState("error");
      setMessage(
        err instanceof Error && err.message
          ? err.message
          : "Không gửi được email, vui lòng thử lại sau",
      );
    }
  };

  return (
    <div className="border-b border-[#ffd875]/30 bg-[#ffd875]/10 px-4 py-3">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-3 gap-y-2">
        <span className="text-sm text-[#3d3d3d] dark:text-[#ffd875]">
          {state === "sent" ? (
            message
          ) : (
            <>
              Tài khoản <strong>{user.email}</strong> chưa xác thực email.
            </>
          )}
        </span>

        {state !== "sent" && (
          <button
            type="button"
            onClick={handleResend}
            disabled={state === "sending"}
            className="rounded-md bg-[#ffd875] px-3 py-1 text-sm font-semibold text-[#121931] transition hover:brightness-95 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {state === "sending" ? "Đang gửi..." : "Gửi lại email xác thực"}
          </button>
        )}

        {state === "error" && (
          <span className="text-sm text-red-500">{message}</span>
        )}

        <button
          type="button"
          onClick={() => setDismissed(true)}
          aria-label="Đóng thông báo"
          className="ml-auto text-sm text-[#8f99bb] transition hover:text-[#3d3d3d] dark:hover:text-white"
        >
          Đóng
        </button>
      </div>
    </div>
  );
}
