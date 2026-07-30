package mocphim.com.backend_web.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mocphim.com.backend_web.security.UserDetailsServiceImpl;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsServiceImpl userDetailsService;

    /**
     * Bộ kiểm tra trạng thái tài khoản (enabled / locked / expired) mà Spring dùng
     * trong DaoAuthenticationProvider ở luồng login. Filter này tự dựng
     * Authentication nên không đi qua provider đó, phải gọi tay — nếu không, tài
     * khoản bị khoá vẫn dùng được access token đã phát trước khi khoá.
     */
    private final UserDetailsChecker userDetailsChecker = new AccountStatusUserDetailsChecker();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (StringUtils.hasText(token)) {
            if (jwtTokenProvider.isTokenExpired(token)) {
                request.setAttribute("jwt_error", "expired");
            } else if (jwtTokenProvider.validateToken(token)) {
                authenticate(request, token);
            } else {
                request.setAttribute("jwt_error", "invalid");
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) throws ServletException {
        UserDetails userDetails;
        try {
            String email = jwtTokenProvider.getEmailFromToken(token);
            userDetails = userDetailsService.loadUserByUsername(email);
        } catch (UsernameNotFoundException ex) {
            // User đã bị xoá nhưng token còn hạn: token thật sự không dùng được nữa.
            request.setAttribute("jwt_error", "invalid");
            return;
        } catch (RuntimeException ex) {
            // Lỗi hạ tầng (DB chưa sẵn sàng, connection pool cạn, timeout...).
            // Không được báo thành 401: client sẽ tưởng token sai rồi xoá session
            // cho một sự cố tạm thời. Để nó nổi lên thành 5xx cho đúng bản chất.
            log.error("Không tra được user khi xác thực JWT", ex);
            throw new ServletException("Không xác thực được do lỗi phía server", ex);
        }

        try {
            userDetailsChecker.check(userDetails);
        } catch (AccountStatusException ex) {
            log.debug("Từ chối token của tài khoản không còn hiệu lực: {}", userDetails.getUsername());
            request.setAttribute("jwt_error", "disabled");
            return;
        }

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return null;
    }
}
