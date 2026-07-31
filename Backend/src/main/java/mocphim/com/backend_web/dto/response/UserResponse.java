package mocphim.com.backend_web.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String email;
    private String name;
    private String avatar;
    private String provider;
    private Set<String> roles;
    private boolean isVerified;
    private boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Viết tay để gắn được @JsonProperty lên chính getter (Lombok bỏ qua khi method đã
     * tồn tại). Không có annotation này thì Jackson rút tiền tố "is" của `isVerified()`
     * thành key `verified`, lệch với tên frontend đọc — hệ quả là bảng người dùng bên
     * admin hiển thị mọi tài khoản thành "Chưa xác thực". Đặt annotation trên field thì
     * không giải quyết được: Jackson sinh ra cả hai key.
     */
    @JsonProperty("isVerified")
    public boolean isVerified() {
        return isVerified;
    }
}
