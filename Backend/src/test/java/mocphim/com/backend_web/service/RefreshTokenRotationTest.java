package mocphim.com.backend_web.service;

import mocphim.com.backend_web.dto.response.TokenResponse;
import mocphim.com.backend_web.model.User;
import mocphim.com.backend_web.repository.UserRepository;
import mocphim.com.backend_web.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.DisabledException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Rotation sai thì hậu quả không phải "một tính năng không chạy" mà là người dùng bị
 * đăng xuất hàng loạt — kiểu lỗi chỉ lộ ra trên production, lúc đã muộn. Nên chốt cả
 * bốn nhánh quyết định bằng test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenRotationTest {

    private static final String OLD_TOKEN = "token-cu";
    private static final String OLD_JTI = "jti-cu";
    private static final Long USER_ID = 7L;

    @Mock private UserRepository userRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshTokenStore refreshTokenStore;

    @InjectMocks private AuthService authService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(USER_ID);
        user.setEmail("a@b.com");
        user.setEnabled(true);
        ReflectionTestUtils.setField(authService, "accessExpiration", 1_800_000L);

        when(jwtTokenProvider.validateToken(OLD_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(OLD_TOKEN)).thenReturn(USER_ID);
        when(jwtTokenProvider.getJtiFromToken(OLD_TOKEN)).thenReturn(OLD_JTI);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(any())).thenReturn("access-moi");
        when(jwtTokenProvider.generateRefreshToken(any(), anyString())).thenReturn("refresh-moi");
        when(refreshTokenStore.issue(USER_ID)).thenReturn("jti-moi");
    }

    @Test
    void refresh_hopLe_thiXoayLuonRefreshToken() {
        when(refreshTokenStore.consume(OLD_JTI)).thenReturn(USER_ID);

        TokenResponse res = authService.refreshToken(OLD_TOKEN);

        // Điểm cốt lõi của rotation: token trả về KHÁC token đưa vào.
        assertThat(res.getRefreshToken()).isEqualTo("refresh-moi").isNotEqualTo(OLD_TOKEN);
        assertThat(res.getAccessToken()).isEqualTo("access-moi");

        // Token cũ phải bị tiêu thụ, và token mới được ghi nhận.
        verify(refreshTokenStore).consume(OLD_JTI);
        verify(refreshTokenStore).issue(USER_ID);
        // Giữ bản thay thế cho tab chạy chậm.
        verify(refreshTokenStore).rememberReplacement(OLD_JTI, "refresh-moi");
        verify(refreshTokenStore, never()).revokeAll(any());
    }

    @Test
    void dungLaiTokenDaXoay_ngoaiThoiGianAnHan_thiThuHoiCaChuoi() {
        when(refreshTokenStore.consume(OLD_JTI)).thenReturn(null);   // jti đã chết
        when(refreshTokenStore.findReplacement(OLD_JTI)).thenReturn(null); // hết ân hạn

        assertThatThrownBy(() -> authService.refreshToken(OLD_TOKEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đăng nhập lại");

        // Không biết bên nào là chủ thật → cắt sạch, buộc đăng nhập lại.
        verify(refreshTokenStore).revokeAll(USER_ID);
    }

    @Test
    void haiTabCungRefresh_trongThoiGianAnHan_thiKhongDaNguoiDungRa() {
        when(refreshTokenStore.consume(OLD_JTI)).thenReturn(null);
        when(refreshTokenStore.findReplacement(OLD_JTI)).thenReturn("refresh-moi");

        TokenResponse res = authService.refreshToken(OLD_TOKEN);

        // Tab chậm chân nhận đúng token mà tab kia vừa nhận, thay vì bị coi là kẻ trộm.
        assertThat(res.getRefreshToken()).isEqualTo("refresh-moi");
        verify(refreshTokenStore, never()).revokeAll(any());
    }

    @Test
    void tokenBanCu_chuaCoJti_thiTuChoi() {
        when(jwtTokenProvider.getJtiFromToken(OLD_TOKEN)).thenReturn(null);

        // Cho qua thì kẻ tấn công chỉ cần dùng token bản cũ là vô hiệu hoá cả cơ chế.
        assertThatThrownBy(() -> authService.refreshToken(OLD_TOKEN))
                .isInstanceOf(IllegalArgumentException.class);

        verify(refreshTokenStore, never()).consume(anyString());
    }

    @Test
    void taiKhoanBiKhoa_thiThuHoiLuonToanBoToken() {
        user.setEnabled(false);
        when(refreshTokenStore.consume(OLD_JTI)).thenReturn(USER_ID);

        assertThatThrownBy(() -> authService.refreshToken(OLD_TOKEN))
                .isInstanceOf(DisabledException.class);

        verify(refreshTokenStore).revokeAll(USER_ID);
    }

    @Test
    void dangXuat_thiThuHoiRefreshToken() {
        authService.logout(OLD_TOKEN);
        verify(refreshTokenStore).revoke(OLD_JTI);
    }
}
