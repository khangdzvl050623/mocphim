package mocphim.com.backend_web.repository;

import mocphim.com.backend_web.entity.MovieSync;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MovieSyncRepository extends JpaRepository<MovieSync, Long> {
    boolean existsBySlug(String slug);
    Optional<MovieSync> findBySlug(String slug);
    Page<MovieSync> findByOphimIdIsNull(Pageable pageable);
    long countByOphimIdIsNull();

    /**
     * Danh sách năm phát hành, mới nhất trước.
     *
     * Lấy từ dữ liệu đã sync thay vì hỏi OPhim: bên đó chỉ có
     * /nam-phat-hanh/{year} (lọc phim theo một năm), còn /nam-phat-hanh trần thì
     * trả 404 nên không có nguồn nào để hỏi. Đổi lại, mọi năm trong danh sách đều
     * chắc chắn có phim xem được.
     */
    @Query("SELECT DISTINCT m.year FROM MovieSync m WHERE m.year IS NOT NULL ORDER BY m.year DESC")
    List<Integer> findDistinctYearsDesc();
}
