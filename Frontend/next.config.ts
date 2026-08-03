// next.config.js hoặc next.config.mjs
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
    transpilePackages: ["antd", "@ant-design/icons", "@ant-design/pro-components", "rc-util", "rc-pagination", "rc-picker"],
    images: {
        /**
         * Tải ảnh thẳng từ CDN, không đi vòng qua /_next/image.
         *
         * Poster lấy từ img.ophim.live. Khi để Next tối ưu, server Vercel phải fetch hộ
         * từng ảnh — và bị CDN đó từ chối hàng loạt (mở cùng URL bằng trình duyệt thì ảnh
         * hiện bình thường, chỉ Vercel fetch mới hỏng), nên toàn bộ poster trả 404. Tắt đi
         * thì trình duyệt tải trực tiếp, đúng cách CDN muốn được gọi.
         *
         * Mất gì: không còn tự chuyển AVIF/WebP và resize theo khung. Với poster thì gần
         * như không thiệt — CDN đã phục vụ sẵn ảnh cỡ phù hợp. Đổi lại còn thoát luôn giới
         * hạn Image Optimization của gói Hobby, vốn rất chật với 1263 phim.
         *
         * Các tuỳ chọn bên dưới giữ nguyên để bật lại dễ dàng nếu sau này đổi nguồn ảnh.
         */
        unoptimized: true,
        formats: ["image/avif", "image/webp"],
        qualities: [65, 70, 72, 75],
        minimumCacheTTL: 60 * 60 * 24 * 30,
        remotePatterns: [
            {
                protocol: "https",
                hostname: "**",
                port: "",
                pathname: "/**",
            },
            {
                protocol: "http",
                hostname: "**",
                port: "",
                pathname: "/**",
            },
        ],
    },
    typescript: {
        ignoreBuildErrors: true,
    },
};

export default nextConfig;
