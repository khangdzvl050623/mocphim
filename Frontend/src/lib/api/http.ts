/**
 * Tầng transport dùng chung cho mọi module gọi API.
 *
 * Mục đích duy nhất: phân biệt hai loại thất bại mà phần lớn code client hay gộp
 * làm một, dẫn tới xoá session oan.
 *
 *   - ApiRejectedError    → server ĐÃ trả lời và từ chối. Kết luận đáng tin.
 *   - ApiUnavailableError → chưa kết luận được gì (mất mạng, 5xx, body không phải
 *                           JSON của mình). Phải giữ nguyên trạng và thử lại.
 *
 * Không phụ thuộc nhà cung cấp hosting nào: mọi quyết định dựa trên status code
 * và việc body có đúng envelope của API hay không. Muốn đổi hành vi thì sửa
 * TRANSIENT_STATUS_CODES hoặc truyền `transientStatuses` cho từng call.
 */

/** Envelope chuẩn của backend: { status, message, data }. */
export interface ApiEnvelope<T> {
  status: boolean;
  message: string;
  data: T;
}

/**
 * Server trả lời rõ ràng là từ chối: sai mật khẩu, token không hợp lệ,
 * tài khoản bị khoá, không đủ quyền. Đây là loại lỗi duy nhất đủ căn cứ để
 * client xoá token.
 */
export class ApiRejectedError extends Error {
  readonly status: number;
  /** Message gốc từ server, để UI hiển thị nguyên văn. */
  readonly serverMessage: string;

  constructor(message: string, status: number) {
    super(message);
    this.name = "ApiRejectedError";
    this.status = status;
    this.serverMessage = message;
  }
}

/**
 * Không kết luận được: mất mạng, request bị chặn, server đang khởi động, hoặc
 * proxy trả HTML thay JSON. Trạng thái đăng nhập phải được giữ nguyên.
 */
export class ApiUnavailableError extends Error {
  /** Có status nếu server/proxy trả về HTTP; không có nếu request không đi được. */
  readonly status?: number;

  constructor(message: string, options?: { status?: number; cause?: unknown }) {
    super(message, options?.cause !== undefined ? { cause: options.cause } : undefined);
    this.name = "ApiUnavailableError";
    this.status = options?.status;
  }
}

/**
 * Status code coi là tạm thời dù nằm ngoài dải 5xx.
 * 408 Request Timeout, 425 Too Early, 429 Too Many Requests.
 */
export const TRANSIENT_STATUS_CODES: readonly number[] = [408, 425, 429];

/** Mọi 5xx đều tạm thời: đó là lỗi phía server, không phải phán quyết về request. */
export function isTransientStatus(
  status: number,
  extra: readonly number[] = [],
): boolean {
  return (
    status >= 500 ||
    TRANSIENT_STATUS_CODES.includes(status) ||
    extra.includes(status)
  );
}

export interface RequestJsonOptions extends RequestInit {
  /** Status code bổ sung cần coi là tạm thời cho riêng call này. */
  transientStatuses?: readonly number[];
}

/**
 * Gọi API và trả về envelope, đã phân loại lỗi.
 *
 * @param url URL tuyệt đối — hàm này không giả định base URL nào, để mọi module
 *            (auth, bookmarks, comments, progress…) dùng được với base riêng.
 */
export async function requestJson<T>(
  url: string,
  options: RequestJsonOptions = {},
): Promise<ApiEnvelope<T>> {
  const { transientStatuses = [], ...init } = options;

  let res: Response;
  try {
    res = await fetch(url, init);
  } catch (err) {
    // fetch chỉ reject vì lỗi mạng/CORS/abort — không bao giờ vì HTTP status.
    throw new ApiUnavailableError("Không kết nối được tới server", { cause: err });
  }

  // Đọc text rồi mới parse. Gọi res.json() trực tiếp sẽ ném SyntaxError khi
  // server hoặc proxy trả HTML (trang lỗi 502/503, captcha, redirect...), và
  // SyntaxError đó rất dễ bị tầng trên hiểu nhầm thành lỗi xác thực.
  const raw = await res.text().catch(() => "");
  let envelope: ApiEnvelope<T> | null = null;
  try {
    envelope = raw ? (JSON.parse(raw) as ApiEnvelope<T>) : null;
  } catch {
    envelope = null;
  }

  // Body không phải envelope của API mình → không phải backend mình trả lời.
  if (!envelope || typeof envelope.status !== "boolean") {
    throw new ApiUnavailableError(`Server chưa sẵn sàng (HTTP ${res.status})`, {
      status: res.status,
    });
  }

  // Kiểm tra status code TRƯỚC khi tin cờ `status` trong body: một 503 kèm
  // envelope hợp lệ vẫn là lỗi tạm thời, không phải phán quyết về request.
  if (isTransientStatus(res.status, transientStatuses)) {
    throw new ApiUnavailableError(
      envelope.message || `Lỗi server (HTTP ${res.status})`,
      { status: res.status },
    );
  }

  if (!res.ok || !envelope.status) {
    throw new ApiRejectedError(
      envelope.message || "Đã có lỗi xảy ra",
      res.status,
    );
  }

  return envelope;
}

/** Tiện dụng: chỉ cần `data`. */
export async function requestData<T>(
  url: string,
  options?: RequestJsonOptions,
): Promise<T> {
  return (await requestJson<T>(url, options)).data;
}

/** Khoảng chờ giữa các lần thử. Phần tử đầu là 0 = thử ngay. */
export const DEFAULT_RETRY_DELAYS_MS: readonly number[] = [0, 1_000, 3_000, 9_000];

/**
 * Backoff dài cho lần khôi phục phiên đầu tiên sau khi mở trang.
 *
 * Backoff mặc định chỉ chờ tổng 13 giây — đủ cho server khởi động lại bình thường,
 * nhưng KHÔNG đủ cho hosting free-tier ngủ khi không có traffic: đánh thức instance
 * mất 30-60 giây. Hết retry sớm thì token vẫn còn nguyên trong localStorage nhưng
 * user state là null, giao diện hiện y như vừa bị đăng xuất.
 *
 * Tổng ~50 giây, chia nhỏ dần để trường hợp server tỉnh sớm vẫn vào nhanh.
 */
export const COLD_START_RETRY_DELAYS_MS: readonly number[] = [
  0, 1_000, 2_000, 4_000, 8_000, 15_000, 20_000,
];

export interface RetryOptions {
  delaysMs?: readonly number[];
  /** Mặc định chỉ thử lại với lỗi tạm thời — không thử lại khi bị từ chối. */
  shouldRetry?: (err: unknown) => boolean;
  /** Trả true để dừng sớm, ví dụ khi component đã unmount. */
  shouldAbort?: () => boolean;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Thử lại một tác vụ theo backoff định trước. Ném lỗi của lần thử cuối.
 *
 * Dùng cho giai đoạn server có thể chưa sẵn sàng (khởi động lại, deploy, scale
 * from zero) — chờ vài giây rẻ hơn nhiều so với đăng xuất người dùng.
 */
export async function withRetry<T>(
  task: () => Promise<T>,
  options: RetryOptions = {},
): Promise<T> {
  const {
    delaysMs = DEFAULT_RETRY_DELAYS_MS,
    shouldRetry = (err) => err instanceof ApiUnavailableError,
    shouldAbort,
  } = options;

  let lastError: unknown;
  for (let attempt = 0; attempt < delaysMs.length; attempt++) {
    if (shouldAbort?.()) break;
    if (delaysMs[attempt] > 0) await sleep(delaysMs[attempt]);
    if (shouldAbort?.()) break;
    try {
      return await task();
    } catch (err) {
      lastError = err;
      if (!shouldRetry(err)) throw err;
    }
  }
  throw lastError ?? new ApiUnavailableError("Tác vụ bị huỷ trước khi hoàn tất");
}
