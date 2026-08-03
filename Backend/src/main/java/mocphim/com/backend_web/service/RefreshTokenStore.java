package mocphim.com.backend_web.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import redis.clients.jedis.UnifiedJedis;

import java.util.Set;
import java.util.UUID;

/**
 * Theo dõi refresh token nào còn dùng được, để xoay token và phát hiện token bị đánh cắp.
 *
 * Vì sao cần: refresh token là JWT stateless, chữ ký hợp lệ nghĩa là dùng được — kể cả
 * khi nó đã bị lộ. Token sống 7 ngày và nằm trong localStorage (JavaScript đọc được),
 * nên một lỗ XSS là kẻ tấn công dùng được suốt tuần mà không ai biết. Đây đúng trường
 * hợp OAuth 2.0 Security BCP khuyến nghị rotation cho public client.
 *
 * Cách hoạt động: mỗi refresh token mang một {@code jti}. Chỉ jti có mặt ở đây mới đổi
 * được token mới, và đổi xong thì jti đó chết ngay. Nếu ai đó dùng lại một jti đã chết
 * mà chữ ký vẫn hợp lệ, nghĩa là có hai bên cùng cầm một token — thu hồi toàn bộ chuỗi
 * của user đó.
 *
 * Đánh đổi: Redis trở thành phụ thuộc bắt buộc của luồng đăng nhập. Redis chết thì
 * không refresh được — nhưng lỗi lúc đó là lỗi hạ tầng (5xx) chứ không phải "token sai",
 * nên frontend giữ nguyên phiên và thử lại, không đá người dùng ra.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenStore {

    /** jti nào còn đổi được → userId. */
    private static final String KEY_JTI = "refresh:jti:";

    /** Tập jti đang sống của một user, để thu hồi cả chuỗi khi phát hiện đánh cắp. */
    private static final String KEY_USER = "refresh:user:";

    /**
     * jti vừa bị xoay → refresh token thay thế, giữ tạm trong ít giây.
     *
     * Không có lớp này, rotation sẽ tự đá người dùng ra khi họ mở nhiều tab: tab A
     * refresh trước và xoay token, tab B vẫn cầm token cũ nên bị coi là kẻ trộm, và
     * cả hai tab cùng văng. Trong khoảng ân hạn, ai dùng lại token vừa xoay sẽ nhận
     * đúng token thay thế thay vì bị thu hồi.
     */
    private static final String KEY_GRACE = "refresh:grace:";

    private static final int GRACE_SECONDS = 30;

    private final UnifiedJedis jedis;

    /** Khớp với hạn của chính refresh token: hết hạn JWT thì bản ghi ở đây cũng vô nghĩa. */
    @Value("${app.jwt.refresh-expiration}")
    private long refreshExpirationMs;

    private int ttlSeconds() {
        return (int) (refreshExpirationMs / 1000);
    }

    /** Cấp một jti mới và ghi nhận nó đang sống. */
    public String issue(Long userId) {
        String jti = UUID.randomUUID().toString();
        int ttl = ttlSeconds();
        jedis.setex(KEY_JTI + jti, ttl, String.valueOf(userId));
        jedis.sadd(KEY_USER + userId, jti);
        jedis.expire(KEY_USER + userId, ttl);
        return jti;
    }

    /**
     * Tiêu thụ một jti: trả userId nếu jti còn sống, đồng thời giết nó ngay.
     *
     * Dùng GETDEL để đọc-và-xoá trong một lệnh — hai request refresh đến cùng lúc thì
     * chỉ một cái nhận được giá trị, cái còn lại thấy null và đi vào nhánh grace.
     *
     * @return userId, hoặc null nếu jti không còn (đã dùng, đã thu hồi, hoặc hết hạn)
     */
    public Long consume(String jti) {
        if (!StringUtils.hasText(jti)) return null;
        String userId = jedis.getDel(KEY_JTI + jti);
        if (userId == null) return null;
        jedis.srem(KEY_USER + userId, jti);
        return Long.valueOf(userId);
    }

    /**
     * Ghi nhớ token thay thế cho jti vừa xoay, phục vụ trường hợp nhiều tab cùng refresh.
     */
    public void rememberReplacement(String oldJti, String replacementToken) {
        if (!StringUtils.hasText(oldJti)) return;
        jedis.setex(KEY_GRACE + oldJti, GRACE_SECONDS, replacementToken);
    }

    /**
     * Token thay thế của một jti vừa bị xoay, nếu còn trong thời gian ân hạn.
     *
     * @return refresh token thay thế, hoặc null nếu đã quá hạn ân hạn — lúc đó việc
     *         dùng lại jti mới thật sự đáng ngờ.
     */
    public String findReplacement(String jti) {
        if (!StringUtils.hasText(jti)) return null;
        return jedis.get(KEY_GRACE + jti);
    }

    /**
     * Thu hồi mọi refresh token của user. Gọi khi phát hiện token bị dùng lại ngoài
     * thời gian ân hạn — không biết bên nào là chủ thật, nên cắt sạch và bắt đăng nhập lại.
     */
    public void revokeAll(Long userId) {
        Set<String> jtis = jedis.smembers(KEY_USER + userId);
        if (jtis != null) {
            for (String jti : jtis) {
                jedis.del(KEY_JTI + jti);
                jedis.del(KEY_GRACE + jti);
            }
        }
        jedis.del(KEY_USER + userId);
        log.warn("Đã thu hồi toàn bộ refresh token của user id={} ({} token)",
                userId, jtis == null ? 0 : jtis.size());
    }

    /** Thu hồi một token cụ thể. Dùng cho đăng xuất. */
    public void revoke(String jti) {
        if (!StringUtils.hasText(jti)) return;
        String userId = jedis.get(KEY_JTI + jti);
        jedis.del(KEY_JTI + jti);
        jedis.del(KEY_GRACE + jti);
        if (userId != null) jedis.srem(KEY_USER + userId, jti);
    }
}
