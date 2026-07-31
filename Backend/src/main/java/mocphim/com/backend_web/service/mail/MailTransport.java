package mocphim.com.backend_web.service.mail;

/**
 * Đường gửi mail. Tách khỏi EmailService vì cách gửi phụ thuộc hạ tầng chứ không
 * phụ thuộc nội dung: Render chặn port SMTP outbound nên bản SMTP không dùng được ở
 * đó, trong khi môi trường khác (VPS, máy dev) thì SMTP lại tiện hơn.
 *
 * Bên gọi chỉ cần biết "gửi hoặc ném lỗi đã được diễn giải" — mỗi bản cài đặt tự
 * dịch lỗi tầng dưới của mình thành {@link MailDeliveryException} có thông điệp chỉ
 * thẳng vào thứ cần sửa.
 */
public interface MailTransport {

    /**
     * @throws MailDeliveryException khi không gửi được, message đã nêu nguyên nhân
     *         cấu hình cụ thể thay vì lỗi kỹ thuật thô.
     */
    void send(String from, String to, String subject, String html);

    /** Mô tả ngắn cấu hình đang dùng — không bao giờ chứa key hay mật khẩu. */
    String describe();
}
