package mocphim.com.backend_web.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import redis.clients.jedis.UnifiedJedis;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Bàn giao phiên đăng nhập OAuth2 từ backend sang frontend mà không đưa token qua URL.
 *
 * Trước đây success handler gắn thẳng accessToken + refreshToken vào query string của
 * trang callback. Chỗ đó lộ ra nhiều nơi ngoài tầm kiểm soát: lịch sử trình duyệt,
 * header Referer khi trang gọi tài nguyên bên thứ ba, và access log của mọi proxy trên
 * đường đi — trong khi refresh token sống 7 ngày.
 *
 * Thay bằng một mã ngẫu nhiên chỉ dùng được một lần, sống 60 giây, tự nó không mang
 * thông tin gì. Frontend đổi mã lấy token qua POST, nơi dữ liệu nằm trong body.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2HandoffService {

    private static final String KEY_PREFIX = "oauth2:handoff:";

    /** Đủ để frontend đổi mã ngay khi trang callback load, không đủ để ai đó nhặt lại sau. */
    private static final int TTL_SECONDS = 60;

    /** 32 byte ngẫu nhiên — không đoán được, cũng không cần dài hơn vì mã sống 60 giây. */
    private static final int CODE_BYTES = 32;

    private final UnifiedJedis jedis;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Phát mã cho user vừa đăng nhập Google thành công. */
    public String issue(Long userId) {
        byte[] buffer = new byte[CODE_BYTES];
        secureRandom.nextBytes(buffer);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
        jedis.setex(KEY_PREFIX + code, TTL_SECONDS, String.valueOf(userId));
        return code;
    }

    /**
     * Đổi mã lấy userId. Dùng GETDEL để đọc và xoá trong một lệnh: hai request đến
     * cùng lúc thì chỉ một cái nhận được giá trị, cái còn lại thấy null.
     *
     * @throws IllegalArgumentException nếu mã sai, đã dùng, hoặc đã hết hạn — cả ba
     *         trường hợp trả cùng một message để không lộ mã nào từng tồn tại.
     */
    public Long consume(String code) {
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("Thiếu mã đăng nhập");
        }
        String userId = jedis.getDel(KEY_PREFIX + code);
        if (userId == null) {
            log.warn("Từ chối đổi mã OAuth2 không hợp lệ hoặc đã dùng");
            throw new IllegalArgumentException("Mã đăng nhập không hợp lệ hoặc đã hết hạn");
        }
        return Long.valueOf(userId);
    }
}
