package mocphim.com.backend_web.service.mail;

/**
 * Không gửi được mail, kèm thông điệp đã diễn giải sang nguyên nhân cấu hình.
 *
 * Tồn tại để chỗ hiển thị lỗi (endpoint test của admin) không phải tự đoán ý nghĩa
 * của SocketTimeoutException hay HTTP 401 đến từ tầng dưới.
 */
public class MailDeliveryException extends RuntimeException {

    public MailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public MailDeliveryException(String message) {
        super(message);
    }
}
