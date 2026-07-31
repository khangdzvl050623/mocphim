package mocphim.com.backend_web.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import mocphim.com.backend_web.security.CustomUserDetails;
import mocphim.com.backend_web.service.OAuth2HandoffService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final OAuth2HandoffService handoffService;

    @Value("${app.oauth2.authorized-redirect-uri}")
    private String redirectUri;

    /**
     * Redirect kèm mã dùng-một-lần thay vì kèm token.
     *
     * URL của trang callback bị ghi vào lịch sử trình duyệt, có thể lọt vào header
     * Referer và nằm trong access log của proxy. Token đặt ở đó là lộ; mã handoff thì
     * sống 60 giây, dùng một lần, và tự nó không mở được gì.
     */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        String code = handoffService.issue(userDetails.getUser().getId());

        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("code", code)
                .build().toUriString();

        clearAuthenticationAttributes(request);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
