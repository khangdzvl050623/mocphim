package mocphim.com.backend_web.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mocphim.com.backend_web.dto.request.LoginRequest;
import mocphim.com.backend_web.dto.request.RegisterRequest;
import mocphim.com.backend_web.dto.response.TokenResponse;
import mocphim.com.backend_web.dto.response.UserResponse;
import mocphim.com.backend_web.model.Role;
import mocphim.com.backend_web.model.User;
import mocphim.com.backend_web.repository.UserRepository;
import mocphim.com.backend_web.security.CustomUserDetails;
import mocphim.com.backend_web.security.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final RestTemplate restTemplate;
    private final EmailService emailService;
    private final OAuth2HandoffService oAuth2HandoffService;
    private final RefreshTokenStore refreshTokenStore;

    @Value("${app.jwt.access-expiration}")
    private long accessExpiration;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String googleClientId;

    public void register(RegisterRequest request) {
        userRepository.findByEmail(request.getEmail()).ifPresent(existing -> {
            if (existing.isVerified()) {
                throw new IllegalArgumentException("Email đã được sử dụng");
            }
            // Chưa verify → cấp lại token và gửi lại mail
            existing.setPassword(passwordEncoder.encode(request.getPassword()));
            existing.setName(request.getName());
            existing.setVerifyToken(UUID.randomUUID().toString());
            existing.setVerifyExpires(LocalDateTime.now().plusHours(24));
            userRepository.save(existing);
            emailService.sendVerificationEmail(existing.getEmail(), existing.getVerifyToken());
            throw new AlreadySentException();
        });

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName());
        user.setProvider("local");
        user.setRoles(new HashSet<>(Set.of(Role.ROLE_USER)));
        user.setVerified(false);
        user.setVerifyToken(UUID.randomUUID().toString());
        user.setVerifyExpires(LocalDateTime.now().plusHours(24));
        userRepository.save(user);
        emailService.sendVerificationEmail(user.getEmail(), user.getVerifyToken());
    }

    // Sentinel exception dùng để thoát sớm khỏi ifPresent mà không cần flag biến
    private static class AlreadySentException extends RuntimeException {
        AlreadySentException() { super(null, null, true, false); }
    }

    public void verifyEmail(String token) {
        User user = userRepository.findByVerifyToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Token xác thực không hợp lệ"));
        if (user.getVerifyExpires() == null || user.getVerifyExpires().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Token xác thực đã hết hạn");
        }
        user.setVerified(true);
        user.setVerifyToken(null);
        user.setVerifyExpires(null);
        userRepository.save(user);
    }

    /**
     * Không lọc theo isVerified nữa.
     *
     * Trước đây tài khoản chưa xác thực email bị bỏ qua im lặng, mà login lại không
     * chặn nhóm này — nên họ dùng bình thường cho tới lúc quên mật khẩu thì không hiểu
     * vì sao mãi không có mail. Link reset gửi tới chính hộp thư đó, ai bấm được nghĩa
     * là sở hữu email — đúng bằng chứng mà bước verify đang đòi hỏi, nên bắt xác thực
     * trước rồi mới cho đặt lại mật khẩu chỉ là bắt đi hai vòng mail cho một nhu cầu.
     */
    public void forgotPassword(String email) {
        Optional<User> target = userRepository.findByEmail(email);
        if (target.isEmpty()) {
            // Response vẫn giữ message chung để không lộ email nào có thật trong hệ thống,
            // nhưng phải log lại: trước đây `ifPresent` im lặng khiến mọi nguyên nhân
            // trông y hệt nhau khi debug.
            log.warn("Bỏ qua yêu cầu quên mật khẩu cho {} — email không tồn tại", email);
            return;
        }

        User user = target.get();
        user.setResetToken(UUID.randomUUID().toString());
        user.setResetExpires(LocalDateTime.now().plusMinutes(15));
        userRepository.save(user);
        log.info("Đã tạo reset token cho {}, chuyển sang hàng đợi gửi mail", email);
        emailService.sendResetPasswordEmail(user.getEmail(), user.getResetToken());
    }

    public void resetPassword(String token, String newPassword) {
        User user = userRepository.findByResetToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Token không hợp lệ"));
        if (user.getResetExpires() == null || user.getResetExpires().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Token đặt lại mật khẩu đã hết hạn");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setResetToken(null);
        user.setResetExpires(null);
        // Bấm được link reset = đọc được hộp thư = đã chứng minh sở hữu email, đúng
        // điều bước verify cần. Nhân tiện gỡ luôn tài khoản khỏi trạng thái kẹt thay vì
        // bắt xác thực thêm một vòng nữa.
        if (!user.isVerified()) {
            user.setVerified(true);
            user.setVerifyToken(null);
            user.setVerifyExpires(null);
            log.info("Đánh dấu đã xác thực cho {} sau khi đặt lại mật khẩu thành công", user.getEmail());
        }
        userRepository.save(user);
    }

    /**
     * Gửi lại mail xác thực. Dành cho người đăng ký xong không bấm link — họ vẫn đăng
     * nhập được bình thường nên không có gì nhắc, tài khoản cứ nằm im ở trạng thái chưa
     * xác thực.
     *
     * Im lặng với email không tồn tại hoặc đã xác thực rồi: cùng một response cho mọi
     * trường hợp, không để endpoint này thành công cụ dò email nào có đăng ký.
     */
    public void resendVerification(String email) {
        Optional<User> target = userRepository.findByEmail(email);
        if (target.isEmpty()) {
            log.warn("Bỏ qua yêu cầu gửi lại mail xác thực cho {} — email không tồn tại", email);
            return;
        }

        User user = target.get();
        if (user.isVerified()) {
            log.info("Bỏ qua yêu cầu gửi lại mail xác thực cho {} — tài khoản đã xác thực", email);
            return;
        }

        user.setVerifyToken(UUID.randomUUID().toString());
        user.setVerifyExpires(LocalDateTime.now().plusHours(24));
        userRepository.save(user);
        log.info("Cấp lại verify token cho {}, chuyển sang hàng đợi gửi mail", email);
        emailService.sendVerificationEmail(user.getEmail(), user.getVerifyToken());
    }

    public TokenResponse login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        CustomUserDetails userDetails = (CustomUserDetails) auth.getPrincipal();
        return buildTokenResponse(userDetails.getUser());
    }

    /**
     * Đổi refresh token lấy cặp token mới, xoay luôn refresh token (rotation).
     *
     * Mỗi refresh token chỉ dùng được một lần. Dùng xong nó chết, người dùng nhận
     * refresh token mới. Nhờ vậy một token bị đánh cắp chỉ sống tới lần refresh kế
     * tiếp, thay vì dùng thoải mái trong 7 ngày như trước.
     *
     * Kèm phát hiện tái sử dụng: nếu ai đó trình một token đã bị xoay (chữ ký vẫn
     * hợp lệ nhưng jti không còn trong store), nghĩa là có hai bên cùng cầm một
     * token — không biết bên nào là chủ thật, nên thu hồi cả chuỗi và bắt đăng nhập lại.
     */
    public TokenResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Refresh token không hợp lệ");
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        String jti = jwtTokenProvider.getJtiFromToken(refreshToken);

        // Token do bản cũ phát chưa có jti. Từ chối thay vì cho qua: chấp nhận token
        // không theo dõi được thì kẻ tấn công chỉ cần dùng token cũ là vô hiệu hoá
        // toàn bộ cơ chế này. Người dùng đăng nhập lại một lần sau khi deploy.
        if (!StringUtils.hasText(jti)) {
            throw new IllegalArgumentException("Phiên đăng nhập đã cũ, vui lòng đăng nhập lại");
        }

        Long ownerId = refreshTokenStore.consume(jti);
        if (ownerId == null) {
            return handleConsumedToken(jti, userId);
        }

        User user = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("User không tồn tại"));
        // Refresh token không mang trạng thái tài khoản, nên phải kiểm tra lại ở đây:
        // không chặn thì tài khoản vừa bị khoá vẫn tự cấp access token mới.
        if (!user.isEnabled()) {
            refreshTokenStore.revokeAll(ownerId);
            throw new DisabledException("Tài khoản đã bị vô hiệu hoá");
        }

        TokenResponse tokens = buildTokenResponse(user);
        // Giữ lại token thay thế trong ít giây, phòng khi một tab khác đang refresh
        // song song với cùng token cũ — xem RefreshTokenStore.KEY_GRACE.
        refreshTokenStore.rememberReplacement(jti, tokens.getRefreshToken());
        return tokens;
    }

    /**
     * Xử lý token có chữ ký hợp lệ nhưng jti không còn trong store.
     *
     * Hai khả năng rất khác nhau: người dùng mở nhiều tab và tab thứ hai chạy chậm
     * vài giây, hay token đã bị đánh cắp. Phân biệt bằng thời gian ân hạn.
     */
    private TokenResponse handleConsumedToken(String jti, Long userId) {
        String replacement = refreshTokenStore.findReplacement(jti);
        if (replacement != null) {
            // Còn trong thời gian ân hạn: trả lại đúng token đã phát cho lần xoay
            // trước, để tab chậm chân không bị đá ra.
            log.debug("Refresh trùng lặp trong thời gian ân hạn, trả lại token thay thế");
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User không tồn tại"));
            return TokenResponse.builder()
                    .accessToken(jwtTokenProvider.generateAccessToken(user))
                    .refreshToken(replacement)
                    .tokenType("Bearer")
                    .expiresIn(accessExpiration)
                    .build();
        }

        log.warn("Phát hiện refresh token bị dùng lại (user id={}) — thu hồi toàn bộ phiên", userId);
        refreshTokenStore.revokeAll(userId);
        throw new IllegalArgumentException("Phiên đăng nhập không còn hợp lệ, vui lòng đăng nhập lại");
    }

    /** Thu hồi refresh token khi đăng xuất, thay vì để nó sống tiếp tới khi hết hạn. */
    public void logout(String refreshToken) {
        if (!StringUtils.hasText(refreshToken) || !jwtTokenProvider.validateToken(refreshToken)) {
            return;
        }
        refreshTokenStore.revoke(jwtTokenProvider.getJtiFromToken(refreshToken));
    }

    /**
     * Đổi mã handoff (do OAuth2SuccessHandler phát) lấy cặp token thật.
     *
     * Mã chỉ dùng được một lần và sống 60 giây, nên tới đây coi như đã xác thực xong —
     * việc còn lại chỉ là kiểm tra tài khoản có còn hiệu lực không, giống hệt luồng
     * refresh: mã có thể được phát ra ngay trước khi admin khoá tài khoản.
     */
    public TokenResponse exchangeOAuth2Code(String code) {
        Long userId = oAuth2HandoffService.consume(code);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User không tồn tại"));
        if (!user.isEnabled()) {
            throw new DisabledException("Tài khoản đã bị vô hiệu hoá");
        }
        log.info("Đổi mã OAuth2 thành công cho user id={}", userId);
        return buildTokenResponse(user);
    }

    @SuppressWarnings("unchecked")
    public TokenResponse loginWithGoogle(String idToken) {
        if (!StringUtils.hasText(idToken)) {
            throw new IllegalArgumentException("ID token không được trống");
        }
        Map<String, Object> googleUser;
        try {
            googleUser = restTemplate.getForObject(
                    "https://oauth2.googleapis.com/tokeninfo?id_token=" + idToken, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Google ID token không hợp lệ hoặc đã hết hạn");
        }
        if (googleUser == null || !googleClientId.equals(googleUser.get("aud"))) {
            throw new IllegalArgumentException("Google ID token không hợp lệ");
        }

        String email = (String) googleUser.get("email");
        String name = (String) googleUser.get("name");
        String avatar = (String) googleUser.get("picture");
        String providerId = (String) googleUser.get("sub");

        User user = userRepository.findByEmail(email).map(existing -> {
            if ("local".equals(existing.getProvider())) {
                throw new IllegalArgumentException("Email đã đăng ký bằng email/password, vui lòng đăng nhập thông thường");
            }
            existing.setName(name);
            existing.setAvatar(avatar);
            return userRepository.save(existing);
        }).orElseGet(() -> {
            User newUser = new User();
            newUser.setEmail(email);
            newUser.setName(name);
            newUser.setAvatar(avatar);
            newUser.setProvider("google");
            newUser.setProviderId(providerId);
            newUser.setVerified(true);
            newUser.setRoles(new HashSet<>(Set.of(Role.ROLE_USER)));
            return userRepository.save(newUser);
        });

        return buildTokenResponse(user);
    }

    @Cacheable(value = "users", key = "#userId")
    public UserResponse getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User không tồn tại"));
        return toUserResponse(user);
    }

    private TokenResponse buildTokenResponse(User user) {
        // jti được ghi nhận vào store trước khi nhúng vào token: chỉ những jti có mặt
        // ở đó mới đổi được token mới, đó là toàn bộ cơ sở của cơ chế rotation.
        String jti = refreshTokenStore.issue(user.getId());
        return TokenResponse.builder()
                .accessToken(jwtTokenProvider.generateAccessToken(user))
                .refreshToken(jwtTokenProvider.generateRefreshToken(user, jti))
                .tokenType("Bearer")
                .expiresIn(accessExpiration)
                .build();
    }

    public UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .avatar(user.getAvatar())
                .provider(user.getProvider())
                .roles(user.getRoles().stream().map(Role::name).collect(Collectors.toSet()))
                .isVerified(user.isVerified())
                .enabled(user.isEnabled())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
