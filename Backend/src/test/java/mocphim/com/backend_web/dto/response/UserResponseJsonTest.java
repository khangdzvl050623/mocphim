package mocphim.com.backend_web.dto.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Khoá tên JSON của cờ xác thực email.
 *
 * Field khai là `boolean isVerified`, Lombok sinh getter `isVerified()`, và Jackson
 * mặc định rút tiền tố "is" thành property `verified` — lệch với tên frontend đang
 * đọc. Lỗi kiểu này không làm build đỏ, chỉ âm thầm hiển thị sai trạng thái, nên
 * chốt bằng test thay vì trông chờ ai đó nhớ.
 */
class UserResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serialize_isVerified_dungTenMaFrontendDoc() throws Exception {
        UserResponse response = UserResponse.builder()
                .id(1L)
                .email("a@b.com")
                .isVerified(true)
                .enabled(true)
                .build();

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"isVerified\":true");
        assertThat(json).doesNotContain("\"verified\":");
    }
}
