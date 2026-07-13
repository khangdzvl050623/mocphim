# Tính năng Progress Watching — Tóm tắt implementation

> Ngày thực hiện: 2026-05-26

---

## Mục tiêu

Lưu tiến trình xem phim (số giây) theo từng tập, để user quay lại xem tiếp đúng chỗ.

---

## Backend (đã xong — chưa push VPS)

### Bảng mới: `watch_progress`
| Column | Type | Ghi chú |
|---|---|---|
| userId | Long | FK user |
| movieId | String | MongoDB `_id` của phim |
| slug | String | slug phim (dùng cho upsert lần đầu) |
| episodeNumber | int | số tập |
| positionSeconds | Long | vị trí đang xem (giây) |
| isCompleted | Boolean | đã xem hết tập chưa |
| lastWatchedAt | LocalDateTime | thời điểm xem gần nhất |

> `ddl-auto=update` tự tạo bảng khi restart, không cần migration thủ công.

### Endpoints

```
GET  /api/v1/progress/{userId}/{movieId}/{episodeNumber}   → lấy progress 1 tập
GET  /api/v1/progress/{userId}/{movieId}                   → lấy tất cả tập đã xem
PATCH /api/v1/progress/{userId}/{movieId}/{episodeNumber}  → upsert progress
```

### PATCH Request Body (`WatchProgressRequestDto`)
```json
{
  "slug": "ten-phim-slug",
  "positionSeconds": 1234,
  "isCompleted": false
}
```

### Response (`WatchProgressResponseDto`)
```json
{
  "userId": 7,
  "movieId": "6a150c3...",
  "slug": "ten-phim-slug",
  "episodeNumber": 1,
  "positionSeconds": 1234,
  "isCompleted": false,
  "lastWatchedAt": "2026-05-26T10:00:00"
}
```

> Redis cache TTL 2h cho mỗi progress entry.

---

## Frontend (đã xong — đã chạy local)

### Các file đã thay đổi

#### 1. `src/lib/api/progress.ts` — **File mới**

Hai hàm gọi API:

```typescript
getWatchProgress(userId, movieId, episodeNumber, accessToken): Promise<number | null>
upsertWatchProgress(userId, movieId, movieSlug, episodeNumber, positionSeconds, isCompleted, accessToken): Promise<void>
```

#### 2. `src/app/(default)/xem-phim/[slug]/types/index.ts`

Thêm `linkM3u8: string` vào `WatchEpisode`.

#### 3. `src/app/(default)/xem-phim/[slug]/index.tsx`

- Map `link_m3u8` khi build danh sách episodes
- Pass `movieId={item._id}` và `linkM3u8` xuống `VideoPlayer`

#### 4. `src/app/(default)/xem-phim/[slug]/components/VideoPlayer/index.tsx` — **Rewrite**

Đổi từ Server Component → `"use client"`, dùng HLS.js thay `<iframe>`.

---

### Logic VideoPlayer

**Ưu tiên nguồn phát:**
- `linkM3u8` có URL → `<video>` + HLS.js (track được progress)
- `linkM3u8` rỗng → fallback `<iframe>` (không track được progress)

**Save progress khi:**
| Event | `isCompleted` |
|---|---|
| pause | false |
| ended (xem hết tập) | true |
| pagehide (đóng tab) | false |
| auto-save mỗi 30 giây | false |

**Restore progress khi mở tập:**
1. Đọc `localStorage` trước (`key: progress_{slug}_{episode}`)
2. Nếu đã login → gọi API lấy vị trí server (server wins nếu có)
3. Seek video đến giây đã lưu (bỏ qua nếu ≤ 5 giây)

**Auth:**
- Token lấy từ `localStorage.getItem("accessToken")`
- userId lấy từ `useAuth().user.id`
- Chưa login → chỉ lưu localStorage, không gọi API

---

### Dependency đã thêm

```
hls.js ^1.6.16
```

> Import động (`await import("hls.js")`) bên trong `useEffect` để tránh SSR issue.

---

## Trạng thái hiện tại

| Hạng mục | Trạng thái |
|---|---|
| HLS player chạy được | ✅ Confirmed qua DevTools (index.m3u8, mixed.m3u8 → 200) |
| Frontend code | ✅ Hoàn chỉnh, không có TypeScript error |
| Backend code | ✅ Hoàn chỉnh local |
| Backend deploy lên VPS | ⏳ Chưa push — API vẫn trả 500 |

> Khi push backend lên VPS và restart, tính năng sẽ hoạt động end-to-end.
