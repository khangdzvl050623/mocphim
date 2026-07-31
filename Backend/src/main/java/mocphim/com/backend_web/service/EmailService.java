package mocphim.com.backend_web.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mocphim.com.backend_web.service.mail.MailTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Dựng nội dung mail và quyết định gửi hay không. Cách gửi thuộc về
 * {@link MailTransport} — SMTP hay HTTP API là chuyện hạ tầng, thay đổi theo nơi
 * deploy chứ không theo nghiệp vụ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final MailTransport mailTransport;

    @Value("${app.url}")
    private String appUrl;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${app.mail.from}")
    private String fromEmail;

    @Async
    public void sendVerificationEmail(String toEmail, String token) {
        String link = appUrl + "/auth/verify-email?token=" + token;
        sendHtmlEmail(toEmail, "Xác thực tài khoản MocPhim", buildVerificationHtml(link));
    }

    @Async
    public void sendResetPasswordEmail(String toEmail, String token) {
        String link = frontendUrl + "/reset-password?token=" + token;
        sendHtmlEmail(toEmail, "Đặt lại mật khẩu MocPhim", buildResetHtml(link));
    }

    /**
     * Gửi mail thử để kiểm chứng cấu hình. Cố ý KHÔNG @Async và không nuốt lỗi:
     * người gọi cần biết ngay Brevo có nhận hay không, thay vì phải mò log.
     */
    public void sendTestEmail(String toEmail) {
        String html = """
                <p>Đây là email kiểm tra cấu hình gửi mail của <strong>MocPhim</strong>.</p>
                <p>Nhận được email này nghĩa là backend gửi mail thành công.</p>
                <p style="color:#888;font-size:13px">Cấu hình đang dùng: %s</p>
                """.formatted(describeConfig());
        sendHtmlEmailOrThrow(toEmail, "Test cấu hình email MocPhim", html);
    }

    /** Tóm tắt cấu hình đang chạy — không bao giờ chứa mật khẩu / API key. */
    public String describeConfig() {
        return "%s, from=%s, appUrl=%s, frontendUrl=%s".formatted(
                mailTransport.describe(), orUnset(fromEmail), orUnset(appUrl), orUnset(frontendUrl));
    }

    private String orUnset(String value) {
        return StringUtils.hasText(value) ? value : "(CHƯA SET)";
    }

    /**
     * Gửi mail và NÉM lỗi ra ngoài. Dùng cho chỗ nào muốn biết kết quả thật —
     * cụ thể là endpoint test của admin.
     *
     * Tách khỏi {@link #sendHtmlEmail} vì hai chỗ gọi có nhu cầu trái ngược: luồng
     * đăng ký / quên mật khẩu chạy @Async nên không ai bắt được exception, còn
     * người đang chẩn đoán cấu hình thì cần đúng cái exception đó.
     */
    public void sendHtmlEmailOrThrow(String toEmail, String subject, String html) {
        if (!StringUtils.hasText(fromEmail)) {
            throw new IllegalStateException(
                    "Chưa cấu hình biến môi trường MAIL_FROM. Giá trị phải là địa chỉ đã Verified "
                            + "trong Brevo (Senders, domains & IPs), không phải SMTP login.");
        }
        mailTransport.send(fromEmail, toEmail, subject, html);
    }

    /**
     * Gửi mail "best effort": log lỗi rồi nuốt. Đúng cho luồng @Async, vì exception
     * ném từ thread khác không tới được người dùng, mà chặn đăng ký chỉ vì gửi mail
     * hỏng thì càng tệ.
     */
    private void sendHtmlEmail(String toEmail, String subject, String html) {
        try {
            sendHtmlEmailOrThrow(toEmail, subject, html);
        } catch (Exception e) {
            // Log kèm stacktrace: trước đây chỉ in getMessage() nên mất sạch mã lỗi,
            // không phân biệt nổi sai key với bị chặn port.
            log.error("KHÔNG gửi được email đến {}: {}", toEmail, e.getMessage(), e);
        }
    }

    private String buildVerificationHtml(String link) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;font-family:Arial,sans-serif;background:#f4f4f4">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f4;padding:40px 0">
                <tr><td align="center">
                  <table width="600" cellpadding="0" cellspacing="0"
                         style="background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.08)">
                    <tr><td style="background:#e50914;padding:30px;text-align:center">
                      <h1 style="color:#fff;margin:0;font-size:28px;letter-spacing:1px">MocPhim</h1>
                    </td></tr>
                    <tr><td style="padding:40px">
                      <h2 style="color:#222;margin-top:0">Xác thực tài khoản</h2>
                      <p style="color:#555;line-height:1.7">Chào bạn,</p>
                      <p style="color:#555;line-height:1.7">
                        Cảm ơn bạn đã đăng ký tài khoản tại <strong>MocPhim</strong>.
                        Nhấn vào nút bên dưới để xác thực email và kích hoạt tài khoản.
                      </p>
                      <p style="color:#555;line-height:1.7">
                        Link xác thực có hiệu lực trong <strong>24 giờ</strong>.
                      </p>
                      <div style="text-align:center;margin:35px 0">
                        <a href="%s"
                           style="background:#e50914;color:#fff;padding:14px 36px;border-radius:4px;
                                  text-decoration:none;font-size:16px;font-weight:bold;display:inline-block">
                          Xác thực tài khoản
                        </a>
                      </div>
                      <p style="color:#999;font-size:13px;word-break:break-all">
                        Hoặc copy link sau vào trình duyệt:<br>
                        <a href="%s" style="color:#e50914">%s</a>
                      </p>
                    </td></tr>
                    <tr><td style="background:#f9f9f9;padding:20px;text-align:center;border-top:1px solid #eee">
                      <p style="color:#aaa;font-size:12px;margin:0">
                        Nếu bạn không đăng ký tài khoản này, hãy bỏ qua email này.
                      </p>
                    </td></tr>
                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(link, link, link);
    }

    private String buildResetHtml(String link) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;font-family:Arial,sans-serif;background:#f4f4f4">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f4;padding:40px 0">
                <tr><td align="center">
                  <table width="600" cellpadding="0" cellspacing="0"
                         style="background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.08)">
                    <tr><td style="background:#e50914;padding:30px;text-align:center">
                      <h1 style="color:#fff;margin:0;font-size:28px;letter-spacing:1px">MocPhim</h1>
                    </td></tr>
                    <tr><td style="padding:40px">
                      <h2 style="color:#222;margin-top:0">Đặt lại mật khẩu</h2>
                      <p style="color:#555;line-height:1.7">Chào bạn,</p>
                      <p style="color:#555;line-height:1.7">
                        Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản <strong>MocPhim</strong> của bạn.
                        Nhấn vào nút bên dưới để tiếp tục.
                      </p>
                      <p style="color:#555;line-height:1.7">
                        Link có hiệu lực trong <strong>15 phút</strong>.
                      </p>
                      <div style="text-align:center;margin:35px 0">
                        <a href="%s"
                           style="background:#e50914;color:#fff;padding:14px 36px;border-radius:4px;
                                  text-decoration:none;font-size:16px;font-weight:bold;display:inline-block">
                          Đặt lại mật khẩu
                        </a>
                      </div>
                      <p style="color:#999;font-size:13px;word-break:break-all">
                        Hoặc copy link sau vào trình duyệt:<br>
                        <a href="%s" style="color:#e50914">%s</a>
                      </p>
                    </td></tr>
                    <tr><td style="background:#f9f9f9;padding:20px;text-align:center;border-top:1px solid #eee">
                      <p style="color:#aaa;font-size:12px;margin:0">
                        Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này.
                        Mật khẩu của bạn sẽ không thay đổi.
                      </p>
                    </td></tr>
                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(link, link, link);
    }
}
