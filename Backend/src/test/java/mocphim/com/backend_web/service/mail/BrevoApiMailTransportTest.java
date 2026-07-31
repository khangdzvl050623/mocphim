package mocphim.com.backend_web.service.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Không thể thử đường này ở máy dev bằng cách gửi thật (sẽ tốn quota và phụ thuộc
 * mạng), mà sai format thì chỉ lộ ra khi đã deploy — nên chốt hợp đồng với Brevo
 * bằng test: đúng endpoint, đúng header key, đúng tên trường trong body.
 */
class BrevoApiMailTransportTest {

    private static final String ENDPOINT = "https://api.brevo.com/v3/smtp/email";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private BrevoApiMailTransport transport;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        transport = new BrevoApiMailTransport(restTemplate, "xkeysib-test", ENDPOINT, "MocPhim");
    }

    @Test
    void gui_dungHopDongCuaBrevo() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("api-key", "xkeysib-test"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sender.email").value("from@mocphim.com"))
                .andExpect(jsonPath("$.sender.name").value("MocPhim"))
                .andExpect(jsonPath("$.to[0].email").value("user@example.com"))
                .andExpect(jsonPath("$.subject").value("Chủ đề"))
                .andExpect(jsonPath("$.htmlContent").value("<p>Nội dung</p>"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"messageId\":\"<abc@brevo>\"}"));

        transport.send("from@mocphim.com", "user@example.com", "Chủ đề", "<p>Nội dung</p>");

        server.verify();
    }

    @Test
    void thieuApiKey_baoNgayThayViGoiApi() {
        BrevoApiMailTransport khongCoKey =
                new BrevoApiMailTransport(restTemplate, "", ENDPOINT, "MocPhim");

        assertThatThrownBy(() -> khongCoKey.send("a@b.com", "c@d.com", "s", "<p>h</p>"))
                .isInstanceOf(MailDeliveryException.class)
                .hasMessageContaining("BREVO_API_KEY");

        // Không được phát sinh request nào khi thiếu cấu hình.
        server.verify();
    }

    @Test
    void loi401_chiRoLaSaiApiKeyChuKhongPhaiSmtpKey() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}"));

        assertThatThrownBy(() -> transport.send("a@b.com", "c@d.com", "s", "<p>h</p>"))
                .isInstanceOf(MailDeliveryException.class)
                .hasMessageContaining("API key")
                .hasMessageContaining("xkeysib-");
    }

    @Test
    void loi400_chiRoSenderChuaVerified() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"invalid_parameter\",\"message\":\"sender not valid\"}"));

        assertThatThrownBy(() -> transport.send("a@b.com", "c@d.com", "s", "<p>h</p>"))
                .isInstanceOf(MailDeliveryException.class)
                .hasMessageContaining("Verified");
    }

    @Test
    void describe_khongLoApiKey() {
        assertThat(transport.describe())
                .contains("brevo-api")
                .doesNotContain("xkeysib-test");
    }
}
