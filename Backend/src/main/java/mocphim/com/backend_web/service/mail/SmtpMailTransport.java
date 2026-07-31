package mocphim.com.backend_web.service.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.util.StringUtils;

/**
 * Gửi mail qua SMTP. Dùng được ở VPS và máy dev; KHÔNG dùng được trên Render vì nhà
 * cung cấp chặn kết nối ra port SMTP (triệu chứng: SocketTimeoutException sau đúng
 * khoảng timeout đã cấu hình, không phải lỗi xác thực).
 */
@Slf4j
@RequiredArgsConstructor
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSender mailSender;
    private final String host;
    private final String port;
    private final String username;

    @Override
    public void send(String from, String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Email gửi thành công đến {} qua SMTP (from={})", to, from);
        } catch (Exception e) {
            throw new MailDeliveryException(diagnose(e), e);
        }
    }

    /**
     * Dịch exception của JavaMail sang nguyên nhân cấu hình cụ thể, để nhìn log là biết
     * phải sửa gì thay vì đoán.
     */
    private String diagnose(Exception e) {
        if (e instanceof MailAuthenticationException) {
            return "SMTP từ chối đăng nhập — kiểm tra MAIL_USERNAME (phải là SMTP login dạng "
                    + "xxxxxx@smtp-brevo.com) và MAIL_PASSWORD (phải là SMTP key, không phải mật khẩu tài khoản)";
        }
        String detail = rootMessage(e);
        if (e instanceof MailSendException) {
            String lower = detail.toLowerCase();
            if (lower.contains("timed out") || lower.contains("timeout")
                    || lower.contains("connect") || lower.contains("network is unreachable")) {
                return "Không mở được kết nối tới SMTP server — nhiều khả năng nhà cung cấp hosting "
                        + "chặn port SMTP outbound. Chuyển sang gửi qua HTTP API bằng cách set "
                        + "BREVO_API_KEY. Chi tiết: " + detail;
            }
            if (lower.contains("sender") || lower.contains("not authorized") || lower.contains("unauthorized")) {
                return "SMTP server từ chối địa chỉ gửi — MAIL_FROM phải là sender đã Verified. Chi tiết: " + detail;
            }
        }
        return detail;
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return StringUtils.hasText(message) ? current.getClass().getSimpleName() + ": " + message
                : current.getClass().getSimpleName();
    }

    @Override
    public String describe() {
        return "transport=smtp, host=%s, port=%s, username=%s".formatted(host, port, username);
    }
}
