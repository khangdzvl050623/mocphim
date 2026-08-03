/**
 * Dựng URL ảnh trên CDN. Một chỗ duy nhất, vì logic này từng bị chép ra sáu nơi
 * (movie.ts, home.ts, actor.ts, hai trang [slug]/page.tsx, FullMoviesClient) và khi
 * sửa lỗi ở một chỗ thì năm chỗ còn lại vẫn hỏng.
 */

const CDN = process.env.NEXT_PUBLIC_CDN_IMAGE ?? '';

/** Thư mục ảnh phim trên CDN. */
export const CDN_MOVIE_PATH = 'uploads/movies';

/**
 * Ghép tên file ảnh với CDN, chấp nhận mọi dạng dữ liệu nguồn trả về.
 *
 * Nguồn không đồng nhất: OPhim proxy qua backend trả kèm sẵn `uploads/movies/`,
 * còn bảng movie_sync lưu tên file trần. Ghép cứng thì một trong hai nhóm thành
 * `.../uploads/movies/uploads/movies/abc-thumb.jpg` và CDN trả 404.
 *
 * @param path  Tên file, đường dẫn tương đối, hoặc URL đầy đủ
 * @param cdn   CDN override (một số API trả APP_DOMAIN_CDN_IMAGE riêng)
 */
export function buildCdnImageUrl(path: string | null | undefined, cdn?: string): string {
  if (!path) return '';
  if (path.startsWith('http')) return path;

  const base = (cdn || CDN).replace(/\/+$/, '');
  const file = path
    .replace(/^\/+/, '')
    .replace(new RegExp(`^${CDN_MOVIE_PATH}/`), '');

  return `${base}/${CDN_MOVIE_PATH}/${file}`;
}
