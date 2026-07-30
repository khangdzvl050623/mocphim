package mocphim.com.backend_web.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import mocphim.com.backend_web.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Filter này tự dựng Authentication nên không đi qua DaoAuthenticationProvider —
 * mọi kiểm tra trạng thái tài khoản phải do chính nó làm. Test khoá lại hành vi đó.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "valid.jwt.token";
    private static final String EMAIL = "user@example.com";

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserDetailsServiceImpl userDetailsService;

    @Mock
    private FilterChain filterChain;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer " + TOKEN);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Token hợp lệ, chữ ký đúng, chưa hết hạn. */
    private void givenTokenIsStructurallyValid() {
        when(jwtTokenProvider.isTokenExpired(TOKEN)).thenReturn(false);
        when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtTokenProvider.getEmailFromToken(TOKEN)).thenReturn(EMAIL);
    }

    private static UserDetails user(boolean enabled) {
        return User.withUsername(EMAIL)
                .password("irrelevant")
                .authorities("ROLE_USER")
                .disabled(!enabled)
                .build();
    }

    @Test
    @DisplayName("Tài khoản còn hiệu lực: gắn authentication vào SecurityContext")
    void setsAuthenticationForEnabledUser() throws Exception {
        givenTokenIsStructurallyValid();
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(user(true));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(EMAIL);
        assertThat(request.getAttribute("jwt_error")).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Tài khoản bị khoá: từ chối dù access token còn hạn")
    void rejectsDisabledUserEvenWithValidToken() throws Exception {
        givenTokenIsStructurallyValid();
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(user(false));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute("jwt_error")).isEqualTo("disabled");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("User đã bị xoá: token thật sự không dùng được nữa → invalid")
    void marksInvalidWhenUserNoLongerExists() throws Exception {
        givenTokenIsStructurallyValid();
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new UsernameNotFoundException("User not found: " + EMAIL));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute("jwt_error")).isEqualTo("invalid");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("DB lỗi: báo thành 5xx, KHÔNG được gắn cờ token invalid")
    void doesNotDisguiseInfrastructureFailureAsAuthFailure() {
        givenTokenIsStructurallyValid();
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() -> filter.doFilterInternal(request, response, filterChain))
                .isInstanceOf(ServletException.class);

        // Đây là điểm cốt lõi: client không được nhận 401 cho một sự cố hạ tầng,
        // vì nó sẽ tưởng token sai rồi xoá session dù token vẫn còn tốt.
        assertThat(request.getAttribute("jwt_error")).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Token hết hạn: gắn cờ expired, không tra DB")
    void marksExpiredWithoutTouchingDatabase() throws Exception {
        when(jwtTokenProvider.isTokenExpired(TOKEN)).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(request.getAttribute("jwt_error")).isEqualTo("expired");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Không có Authorization header: đi tiếp, không gắn cờ lỗi")
    void passesThroughWhenNoTokenPresent() throws Exception {
        MockHttpServletRequest anonymous = new MockHttpServletRequest();

        filter.doFilterInternal(anonymous, response, filterChain);

        assertThat(anonymous.getAttribute("jwt_error")).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(anonymous, response);
    }
}
