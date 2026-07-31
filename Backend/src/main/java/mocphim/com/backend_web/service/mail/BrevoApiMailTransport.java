package mocphim.com.backend_web.service.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Gửi mail qua Brevo HTTP API v3 (POST https://api.brevo.com/v3/smtp/email).
 *
 * Lý do tồn tại: Render chặn kết nối ra port SMTP, mọi lần gửi qua
 * smtp-relay.brevo.com:587 đều kết thúc bằng SocketTimeoutException. API này đi qua
 * cổng 443 như một request HTTPS bình thường nên không bị chặn.
 *
 * Lưu ý key: đây là API key (tab "API keys" trong Brevo, tiền tố xkeysib-), KHÁC với
 * SMTP key dùng cho đường 587.
 */
@Slf4j
@RequiredArgsConstructor
public class BrevoApiMailTransport implements MailTransport {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String endpoint;
    private final String senderName;

    @Override
    public void send(String from, String to, String subject, String html) {
        if (!StringUtils.hasText(apiKey)) {
            throw new MailDeliveryException(
                    "Chưa cấu hình BREVO_API_KEY. Lấy ở Brevo → Settings → SMTP & API → tab "
                            + "\"API keys\" (tiền tố xkeysib-), không phải SMTP key.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("api-key", apiKey);

        Map<String, Object> body = Map.of(
                "sender", Map.of("email", from, "name", senderName),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "htmlContent", html);

        try {
            restTemplate.postForEntity(endpoint, new HttpEntity<>(body, headers), String.class);
            log.info("Email gửi thành công đến {} qua Brevo API (from={})", to, from);
        } catch (HttpStatusCodeException e) {
            throw new MailDeliveryException(diagnoseHttp(e), e);
        } catch (ResourceAccessException e) {
            throw new MailDeliveryException(
                    "Không gọi được Brevo API — kiểm tra kết nối mạng ra ngoài của server. Chi tiết: "
                            + e.getMessage(), e);
        } catch (Exception e) {
            throw new MailDeliveryException("Lỗi không xác định khi gọi Brevo API: " + e.getMessage(), e);
        }
    }

    /** Brevo trả JSON {code, message}; nội dung đó hữu ích hơn nhiều so với mã HTTP trần. */
    private String diagnoseHttp(HttpStatusCodeException e) {
        String detail = e.getResponseBodyAsString();
        int status = e.getStatusCode().value();
        return switch (status) {
            case 401 -> "Brevo từ chối API key — kiểm tra BREVO_API_KEY (phải là API key tiền tố "
                    + "xkeysib-, không phải SMTP key). Chi tiết: " + detail;
            case 400 -> "Brevo từ chối nội dung gửi — thường là sender chưa Verified trong "
                    + "Senders, domains & IPs. Chi tiết: " + detail;
            case 402 -> "Hết quota gửi của tài khoản Brevo. Chi tiết: " + detail;
            default -> "Brevo API trả HTTP " + status + ". Chi tiết: " + detail;
        };
    }

    @Override
    public String describe() {
        return "transport=brevo-api, endpoint=%s, apiKey=%s".formatted(
                endpoint, StringUtils.hasText(apiKey) ? "(đã set)" : "(CHƯA SET)");
    }
}
