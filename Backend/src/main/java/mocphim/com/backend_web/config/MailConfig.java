package mocphim.com.backend_web.config;

import lombok.extern.slf4j.Slf4j;
import mocphim.com.backend_web.service.mail.BrevoApiMailTransport;
import mocphim.com.backend_web.service.mail.MailTransport;
import mocphim.com.backend_web.service.mail.SmtpMailTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Configuration
public class MailConfig {

    @Value("${brevo.api.key:}")
    private String brevoApiKey;

    @Value("${brevo.api.url}")
    private String brevoApiUrl;

    @Value("${brevo.sender-name}")
    private String senderName;

    @Value("${spring.mail.host}")
    private String mailHost;

    @Value("${spring.mail.port}")
    private String mailPort;

    @Value("${spring.mail.username}")
    private String mailUsername;

    /**
     * Chọn đường gửi mail theo môi trường: có BREVO_API_KEY thì đi HTTP API, không thì
     * quay về SMTP.
     *
     * Không dùng cờ bật/tắt riêng vì sẽ có hai thứ phải nhớ giữ đồng bộ. Sự hiện diện
     * của API key đã là ý định rõ ràng: nơi nào chặn port SMTP (Render) thì set key,
     * nơi nào SMTP chạy tốt (VPS, máy dev) thì không cần.
     */
    @Bean
    public MailTransport mailTransport(RestTemplate restTemplate, JavaMailSender mailSender) {
        if (StringUtils.hasText(brevoApiKey)) {
            log.info("Gửi mail qua Brevo HTTP API ({})", brevoApiUrl);
            return new BrevoApiMailTransport(restTemplate, brevoApiKey, brevoApiUrl, senderName);
        }
        log.info("Gửi mail qua SMTP {}:{} — set BREVO_API_KEY nếu hosting chặn port SMTP",
                mailHost, mailPort);
        return new SmtpMailTransport(mailSender, mailHost, mailPort, mailUsername);
    }
}
