package mocphim.com.backend_web.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mocphim.com.backend_web.dto.response.ApiResponse;
import mocphim.com.backend_web.dto.response.UserResponse;
import mocphim.com.backend_web.service.AdminService;
import mocphim.com.backend_web.service.EmailService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final EmailService emailService;

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Object>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<UserResponse> result = adminService.getAllUsers(page, size);
        ApiResponse.Pagination pagination = new ApiResponse.Pagination(
                result.getNumber() + 1,
                result.getTotalPages(),
                result.getTotalElements(),
                result.getSize());
        return ResponseEntity.ok(ApiResponse.success(result.getContent(), pagination));
    }

    /**
     * Kiểm chứng cấu hình gửi mail. Luồng đăng ký / quên mật khẩu chạy @Async và
     * nuốt lỗi (cố ý), nên khi mail không tới thì không có cách nào biết hỏng ở đâu.
     * Endpoint này gửi đồng bộ và trả nguyên nhân thật về response.
     *
     * GET /api/v1/admin/email-config  → xem cấu hình đang chạy, không gửi gì
     * POST /api/v1/admin/test-email?to=you@gmail.com → gửi thật
     */
    @GetMapping("/email-config")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> emailConfig() {
        return ResponseEntity.ok(ApiResponse.success(
                "Cấu hình email đang chạy (không bao gồm mật khẩu)",
                Map.of("config", emailService.describeConfig())));
    }

    @PostMapping("/test-email")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, String>>> testEmail(@RequestParam("to") String to) {
        try {
            emailService.sendTestEmail(to);
            return ResponseEntity.ok(ApiResponse.success(
                    "Đã gửi thành công đến " + to + ". Không thấy trong hộp thư đến thì kiểm tra mục Spam.",
                    Map.of("config", emailService.describeConfig())));
        } catch (Exception e) {
            log.error("Test gửi mail đến {} thất bại", to, e);
            ApiResponse<Map<String, String>> body = ApiResponse.error(e.getMessage());
            body.setData(Map.of("config", emailService.describeConfig()));
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }
    }
}
