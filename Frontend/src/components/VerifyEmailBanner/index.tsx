"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/contexts/AuthContext";
import { apiResendVerification } from "@/lib/api/auth";

/**
 * Nhắc người dùng chưa xác thực email, kèm nút gửi lại.
 *
 * Cần thiết vì đăng nhập KHÔNG kiểm tra isVerified (xem CustomUserDetails.isEnabled,
 * chỉ đọc cột `enabled`). Người đăng ký xong không bấm link vẫn dùng bình thường,
 * nên không có gì cho họ biết tài khoản đang ở trạng thái chưa xác thực.
 *
 * Nổi ở góc dưới thay vì chèn một dải trên đầu trang: Header là overlay `fixed z-50`
 * nằm đè lên hero, nên mọi thứ đặt ở đầu luồng tài liệu đều bị nó phủ lên — vừa che
 * mất chữ vừa nuốt luôn cú bấm.
 */

/** Nhớ trong phiên làm việc, để đóng rồi thì chuyển trang không thấy hiện lại. */
const DISMISS_KEY = "verifyBannerDismissed";

export default function VerifyEmailBanner() {
  const { user } = useAuth();
  const [state, setState] = useState<"idle" | "sending" | "sent" | "error">("idle");
  const [message, setMessage] = useState("");
  const [dismissed, setDismissed] = useState(true);
  const [shown, setShown] = useState(false);

  // Đọc sessionStorage trong effect chứ không phải lúc khởi tạo state: server render
  // không có sessionStorage, đọc thẳng sẽ lệch với client và gây hydration mismatch.
  useEffect(() => {
    setDismissed(sessionStorage.getItem(DISMISS_KEY) === "1");
  }, []);

  // Hoãn một nhịp rồi mới trượt lên, nếu không thẻ đã nằm sẵn ở vị trí cuối khi
  // trình duyệt vẽ khung hình đầu tiên và không thấy chuyển động nào.
  useEffect(() => {
    if (dismissed) return;
    const timer = setTimeout(() => setShown(true), 50);
    return () => clearTimeout(timer);
  }, [dismissed]);

  // Tài khoản Google luôn được đánh dấu đã xác thực nên không bao giờ rơi vào đây.
  if (!user || user.isVerified || dismissed) return null;

  const close = () => {
    sessionStorage.setItem(DISMISS_KEY, "1");
    setDismissed(true);
  };

  const handleResend = async () => {
    setState("sending");
    try {
      setMessage(await apiResendVerification(user.email));
      setState("sent");
      // Đã báo xong thì tự dọn, không bắt người dùng bấm thêm lần nữa.
      setTimeout(close, 6000);
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
    <div
      role="status"
      className={`fixed bottom-4 left-4 z-[60] w-[min(24rem,calc(100vw-2rem))] rounded-xl border
        border-[#ffd875]/40 bg-white shadow-2xl transition-all duration-300 dark:bg-[#20222e]
        sm:left-auto sm:right-4
        ${shown ? "translate-y-0 opacity-100" : "translate-y-3 opacity-0"}`}
    >
      <div className="flex gap-3 p-4">
        <span
          aria-hidden
          className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#ffd875] text-sm font-bold text-[#121931]"
        >
          !
        </span>

        <div className="min-w-0 flex-1">
          {state === "sent" ? (
            <p className="text-sm text-[#3d3d3d] dark:text-[#dfe4f2]">{message}</p>
          ) : (
            <>
              <p className="text-sm font-semibold text-[#121931] dark:text-white">
                Tài khoản chưa xác thực email
              </p>
              <p className="mt-0.5 truncate text-xs text-[#6b7280] dark:text-[#8f99bb]">
                {user.email}
              </p>

              {state === "error" && (
                <p className="mt-2 text-xs text-red-500">{message}</p>
              )}

              <button
                type="button"
                onClick={handleResend}
                disabled={state === "sending"}
                className="mt-3 h-9 w-full rounded-lg bg-[#ffd875] text-sm font-bold text-[#121931] transition hover:brightness-95 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {state === "sending" ? "Đang gửi..." : "Gửi lại email xác thực"}
              </button>
            </>
          )}
        </div>

        <button
          type="button"
          onClick={close}
          aria-label="Đóng thông báo"
          className="-mr-1 -mt-1 h-7 w-7 shrink-0 rounded-lg text-lg leading-none text-[#8f99bb] transition hover:bg-black/5 hover:text-[#121931] dark:hover:bg-white/10 dark:hover:text-white"
        >
          ×
        </button>
      </div>
    </div>
  );
}
