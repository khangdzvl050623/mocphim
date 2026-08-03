package mocphim.com.backend_web.service;

import lombok.RequiredArgsConstructor;
import mocphim.com.backend_web.repository.MovieSyncRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class YearService {

    private final OPhimService ophimService;
    private final MovieSyncRepository movieSyncRepository;

    /**
     * Danh sách năm lấy từ DB, không gọi OPhim.
     *
     * OPhim chỉ hỗ trợ /nam-phat-hanh/{year}; gọi /nam-phat-hanh không tham số luôn
     * trả 404 kèm {"status":false}, khiến endpoint này trước đây trả 502 100% số lần
     * và trang năm phát hành luôn rỗng. Không có endpoint thay thế nào bên OPhim, nên
     * dựng danh sách từ chính dữ liệu đã sync.
     */
    @Cacheable("years")
    public List<Integer> getYears() {
        return movieSyncRepository.findDistinctYearsDesc();
    }

    @Cacheable(value = "years", key = "#year + '_' + #params.hashCode()")
    public Object getYearMovies(int year, Map<String, String> params) {
        return ophimService.get("/nam-phat-hanh/" + year, params);
    }
}
