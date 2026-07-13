# MocPhim — Sequence Diagrams

> Sử dụng [Mermaid](https://mermaid.js.org/) syntax. Render trực tiếp trên GitHub / VS Code / Obsidian.

---

## 1. Đăng ký & Xác thực Email

```mermaid
sequenceDiagram
    actor FE as Frontend
    participant BE as Backend
    participant DB as PostgreSQL
    participant Mail as Email Service

    FE->>BE: POST /auth/register { email, password, name }
    BE->>DB: Kiểm tra email tồn tại chưa
    alt Email đã tồn tại và đã verify
        DB-->>BE: Đã tồn tại
        BE-->>FE: 400 "Email đã được sử dụng"
    else Email chưa tồn tại / chưa verify
        BE->>DB: Lưu user (enabled=false) hoặc cập nhật token mới
        BE->>Mail: Gửi email xác thực (link có token UUID, hết hạn 24h)
        BE-->>FE: 200 "Đăng ký thành công! Kiểm tra email để xác thực."
    end

    Note over FE,Mail: User nhận email, click link xác thực

    FE->>BE: GET /auth/verify-email?token=xxx
    BE->>DB: Tra cứu token
    alt Token hợp lệ & còn hạn
        BE->>DB: enabled=true, xóa token (single-use)
        BE-->>FE: Redirect /login?verified=true
    else Token không hợp lệ
        BE-->>FE: Redirect /login?error=Token+xác+thực+không+hợp+lệ
    else Token hết hạn
        BE-->>FE: Redirect /login?error=Token+xác+thực+đã+hết+hạn
    end
```

---

## 2. Đăng nhập Local (Email/Password)

```mermaid
sequenceDiagram
    actor FE as Frontend
    participant BE as Backend
    participant DB as PostgreSQL
    participant Redis

    FE->>BE: POST /auth/login { email, password }
    BE->>DB: Tìm user theo email
    alt User không tồn tại hoặc sai password
        BE-->>FE: 401 "Bad credentials"
    else User chưa verify email
        BE-->>FE: 403 "Tài khoản chưa xác thực email"
    else Hợp lệ
        BE->>BE: Tạo accessToken (JWT, 30 phút) + refreshToken (JWT, 7 ngày)
        BE->>Redis: Lưu refreshToken (TTL 7 ngày)
        BE-->>FE: 200 { accessToken, refreshToken, tokenType, expiresIn }
        FE->>FE: localStorage.setItem(accessToken, refreshToken)
    end
```

---

## 3. Làm mới Access Token (Refresh)

```mermaid
sequenceDiagram
    actor FE as Frontend
    participant BE as Backend
    participant Redis

    Note over FE: API trả về 401 → thử refresh

    FE->>BE: POST /auth/refresh { refreshToken }
    BE->>Redis: Kiểm tra refreshToken còn hiệu lực không
    alt Token không hợp lệ hoặc hết hạn
        BE-->>FE: 400 "Refresh token không hợp lệ"
        FE->>FE: Xóa token, redirect /login
    else Hợp lệ
        BE->>BE: Tạo accessToken mới
        BE-->>FE: 200 { accessToken (mới), refreshToken (cũ giữ nguyên) }
        FE->>FE: Cập nhật localStorage, retry request gốc
    end
```

---

## 4. Đăng nhập Google OAuth2

```mermaid
sequenceDiagram
    actor User
    actor FE as Frontend
    participant BE as Backend
    participant Google
    participant DB as PostgreSQL

    FE->>User: Redirect browser đến /oauth2/authorize/google
    User->>Google: Chọn tài khoản Google
    Google->>BE: Callback với authorization code
    BE->>Google: Đổi code lấy profile (email, name, avatar)
    BE->>DB: Kiểm tra email
    alt Email đã đăng ký bằng local
        BE-->>FE: Redirect /oauth2/callback/google?error=email_conflict
        FE->>FE: Redirect /login?error=email_conflict
    else Lần đầu đăng nhập Google
        BE->>DB: Tạo user mới (provider=google, enabled=true)
        BE->>BE: Tạo JWT
        BE-->>FE: Redirect /oauth2/callback/google?accessToken=...&refreshToken=...
        FE->>FE: Lưu token, redirect /
    else Đã login Google trước đó
        BE->>BE: Tạo JWT
        BE-->>FE: Redirect /oauth2/callback/google?accessToken=...&refreshToken=...
        FE->>FE: Lưu token, redirect /
    end
```

---

## 5. Quên mật khẩu & Đặt lại mật khẩu

```mermaid
sequenceDiagram
    actor FE as Frontend
    participant BE as Backend
    participant DB as PostgreSQL
    participant Mail as Email Service

    FE->>BE: POST /auth/forgot-password { email }
    BE->>DB: Tìm user theo email
    alt User tồn tại và đã verify
        BE->>DB: Lưu reset token (UUID, hết hạn 15 phút)
        BE->>Mail: Gửi email link đặt lại mật khẩu
    end
    Note over BE: Luôn trả cùng message (chống dò email)
    BE-->>FE: 200 "Nếu email tồn tại, bạn sẽ nhận được hướng dẫn."

    Note over FE,Mail: User click link trong email → /reset-password?token=xxx

    FE->>BE: POST /auth/reset-password { token, newPassword }
    BE->>DB: Tra cứu token
    alt Token hợp lệ & còn hạn
        BE->>DB: Cập nhật password (BCrypt), xóa token (single-use)
        BE-->>FE: 200 "Đặt lại mật khẩu thành công!"
        FE->>FE: Redirect /login?reset=success
    else Token không hợp lệ
        BE-->>FE: 400 "Token không hợp lệ"
    else Token hết hạn
        BE-->>FE: 400 "Token đặt lại mật khẩu đã hết hạn"
    end
```

---

## 6. Gọi API với Auth (JWT Filter)

```mermaid
sequenceDiagram
    actor FE as Frontend
    participant Filter as JwtAuthFilter
    participant BE as Backend

    FE->>Filter: Request + Authorization: Bearer <accessToken>
    Filter->>Filter: Validate JWT (chữ ký HS512, hết hạn chưa)
    alt JWT không hợp lệ / hết hạn
        Filter-->>FE: 401 Unauthorized
    else JWT hợp lệ
        Filter->>BE: Gắn SecurityContext, chuyển request
        BE-->>FE: 200 + Response data
    end
```

---

## 7. Bookmark

```mermaid
sequenceDiagram
    actor FE as Frontend
    participant BE as Backend
    participant DB as PostgreSQL

    Note over FE,DB: Thêm bookmark

    FE->>BE: POST /api/bookmarks { slug } + Bearer token
    BE->>DB: Lấy userId từ token, tra movieId theo slug
    alt Đã bookmark rồi
        BE-->>FE: 400 "Already bookmarked"
    else Phim chưa sync
        BE-->>FE: 400 "Phim chưa được đồng bộ"
    else OK
        BE->>DB: Lưu bookmark record
        BE-->>FE: 200 { bookmark object }
    end

    Note over FE,DB: Xem danh sách bookmark

    FE->>BE: GET /api/bookmarks/{userId} + Bearer token
    BE->>BE: Kiểm tra userId trong path == userId trong token
    alt Không khớp
        BE-->>FE: 403 Forbidden
    else OK
        BE->>DB: Query bookmark kèm tiến trình xem gần nhất (JOIN WatchProgress)
        BE-->>FE: 200 [ { bookmark + latestEpisode, positionSeconds, ... } ]
    end

    Note over FE,DB: Kiểm tra trạng thái nút bookmark

    FE->>BE: GET /api/bookmarks/isBookmarked/{userId}/{movieId}
    BE->>DB: Kiểm tra tồn tại record
    BE-->>FE: 200 { data: true | false }

    Note over FE,DB: Xóa bookmark

    FE->>BE: DELETE /api/bookmarks/{userId}/{movieId} + Bearer token
    BE->>DB: Xóa record
    BE-->>FE: 200 "Xóa bookmark thành công"
```

---

## 8. Watch Progress (Tiến trình xem)

```mermaid
sequenceDiagram
    actor FE as Frontend
    participant BE as Backend
    participant DB as PostgreSQL
    participant Redis

    Note over FE,Redis: Mở trang phim — tìm điểm tiếp tục

    FE->>BE: GET /api/v1/progress/{userId}/resume/{slug}
    BE->>Redis: Kiểm tra cache wp:{userId}:{movieId}:*
    alt Cache hit
        Redis-->>BE: Trả tiến trình
    else Cache miss
        BE->>DB: Tìm tập có lastWatchedAt mới nhất
        BE->>Redis: Cache kết quả (TTL 2h)
    end
    alt Chưa xem lần nào
        BE-->>FE: 200 { data: null }
        FE->>FE: Load tập 1
    else Đã có tiến trình
        BE-->>FE: 200 { episodeNumber, positionSeconds, ... }
        FE->>FE: Redirect xem tập N
    end

    Note over FE,Redis: Khi player load xong (MANIFEST_PARSED)

    FE->>BE: GET /api/v1/progress/{userId}/{movieId}/{episodeNumber}
    BE->>DB: Lấy positionSeconds của tập cụ thể
    BE-->>FE: 200 { positionSeconds }
    FE->>FE: video.currentTime = positionSeconds (nếu > 5s)

    Note over FE,Redis: Đang xem — lưu tiến trình mỗi 30s

    loop Mỗi 30s / pause / ended / pagehide
        FE->>FE: localStorage[progress_{slug}_{ep}] = currentTime
        FE->>BE: PATCH /api/v1/progress/{userId}/{movieId}/{ep} { slug, positionSeconds, isCompleted }
        BE->>DB: Upsert WatchProgress
        BE->>Redis: Invalidate cache
        BE-->>FE: 200 { updated record }
    end
```

---

## 9. Comments (Bình luận)

```mermaid
sequenceDiagram
    actor FE as Frontend
    participant BE as Backend
    participant DB as PostgreSQL

    Note over FE,DB: Load bình luận (public, token tuỳ chọn)

    FE->>BE: GET /api/v1/comments/{slug}?page=0&size=10 [+ Bearer token]
    BE->>DB: Query comment approved, kèm replies (tối đa 5), phân trang
    alt Có token
        BE->>DB: Kèm userVote của user hiện tại
    end
    BE-->>FE: 200 [ { comment + replies[] + userVote } ] + pagination

    Note over FE,DB: Đăng bình luận (cần login)

    FE->>BE: POST /api/v1/comments/{slug} { content, isSpoiler, parentId? } + Bearer token
    BE->>DB: Lưu comment (status=approved ngay)
    BE-->>FE: 200 { comment object }

    Note over FE,DB: Vote bình luận

    FE->>BE: POST /api/v1/comments/{id}/vote { voteType: "up"|"down" } + Bearer token
    BE->>DB: Kiểm tra vote hiện tại của user
    alt Chưa vote
        BE->>DB: Tạo vote mới, tăng upvotes/downvotes
    else Vote cùng loại (undo)
        BE->>DB: Xóa vote, giảm upvotes/downvotes
    else Vote khác loại (đổi)
        BE->>DB: Cập nhật vote, điều chỉnh upvotes + downvotes
    end
    BE-->>FE: 200 { comment với upvotes/downvotes/userVote mới }

    Note over FE,DB: Xóa bình luận

    FE->>BE: DELETE /api/v1/comments/{id} + Bearer token
    BE->>BE: Kiểm tra quyền (chủ comment hoặc ROLE_ADMIN)
    alt Không có quyền
        BE-->>FE: 403 Forbidden
    else OK
        BE->>DB: Xóa comment
        BE-->>FE: 200 "Đã xóa bình luận"
    end
```

---

## 10. Views (Lượt xem)

```mermaid
sequenceDiagram
    actor FE as Frontend
    participant BE as Backend
    participant DB as PostgreSQL
    participant Redis

    Note over FE,Redis: Ghi nhận lượt xem (gọi 1 lần khi video bắt đầu phát)

    FE->>BE: POST /api/v1/views/{slug} [+ Bearer token]
    alt Đã login
        BE->>Redis: Kiểm tra key view:{slug}:u:{userId} (TTL 24h)
    else Chưa login
        BE->>Redis: Kiểm tra key view:{slug}:ip:{ip} (TTL 24h)
    end
    alt Key tồn tại (đã xem trong 24h)
        BE-->>FE: 200 { viewCount, viewCountToday } (không tăng)
    else Chưa xem / hết cooldown
        BE->>DB: Tăng viewCount + viewCountToday
        BE->>Redis: Set key với TTL 24h
        BE-->>FE: 200 { viewCount (mới), viewCountToday (mới) }
    end

    Note over FE,Redis: Lấy view count nhiều phim (trang listing)

    FE->>BE: GET /api/v1/views/batch?slugs=one-piece,vincenzo,naruto
    BE->>DB: Query viewCount theo danh sách slug
    BE-->>FE: 200 { "one-piece": 1024, "vincenzo": 5832, ... }
    FE->>FE: movies.forEach(m => m.viewCount = data[m.slug] ?? 0)
```

---

## 11. Admin — Sync Phim từ OPhim

```mermaid
sequenceDiagram
    actor Admin
    participant BE as Backend
    participant OPhim as OPhim Public API
    participant DB as PostgreSQL
    participant Redis

    Admin->>BE: POST /api/v1/sync/movies/trigger + Bearer (ROLE_ADMIN)
    BE->>BE: Kiểm tra ROLE_ADMIN
    alt Không có quyền
        BE-->>Admin: 403 Forbidden
    else OK
        loop Từng trang phim từ OPhim
            BE->>OPhim: GET danh sách phim (page N)
            OPhim-->>BE: Danh sách phim + metadata
            BE->>DB: Upsert movies (slug làm unique key)
        end
        BE->>Redis: Invalidate cache movieList, home, syncedMovies
        BE-->>Admin: 200 "Sync hoàn tất"
    end
```
