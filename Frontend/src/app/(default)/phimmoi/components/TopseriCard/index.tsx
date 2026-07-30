"use client";

import React from "react";
import { Swiper, SwiperSlide } from "swiper/react";
import { Navigation } from "swiper/modules";
import { TopSeriesCard } from "@/app/(default)/phimmoi/components/TopseriCard/components/TopSeriesCard";

interface TopSeriesMovie {
  title: string;
  alias: string;
  slug: string;
  thumb: string;
  episodeText: string;
  badges: { type: "pd" | "lt" | "tm"; text: string }[];
}

interface TopSeriesListProps {
  movies?: TopSeriesMovie[];
}

export const TopSeriesList = ({ movies }: TopSeriesListProps) => {
  // Không có fallback hardcode: khi backend không phản hồi thì ẩn hẳn section.
  // Dữ liệu giả sẽ hiện thông tin tập sai và trỏ ảnh sang domain ngoài, biến
  // một sự cố backend thành lỗi ảnh 502 gây nhiễu khi debug.
  const topMovies = movies ?? [];
  const swiperRef = React.useRef<{ update: () => void; destroyed: boolean; setBreakpoint?: () => void; updateSize?: () => void; updateSlides?: () => void; updateProgress?: () => void; updateSlidesClasses?: () => void } | null>(null);
  const [isMounted, setIsMounted] = React.useState(false);

  React.useEffect(() => {
    setIsMounted(true);

    const refreshSwiper = () => {
      const swiper = swiperRef.current;
      if (!swiper || swiper.destroyed) return;

      const relayout = () => {
        if (swiper.destroyed) return;
        // Re-apply breakpoint first, then recalc sizes/slides to avoid oversize cards.
        if (typeof swiper.setBreakpoint === "function") swiper.setBreakpoint();
        if (typeof swiper.updateSize === "function") swiper.updateSize();
        if (typeof swiper.updateSlides === "function") swiper.updateSlides();
        if (typeof swiper.updateProgress === "function") swiper.updateProgress();
        if (typeof swiper.updateSlidesClasses === "function") swiper.updateSlidesClasses();
        swiper.update();
      };

      requestAnimationFrame(relayout);
      requestAnimationFrame(() => requestAnimationFrame(relayout));

      setTimeout(() => {
        relayout();
      }, 160);
    };

    const onVisibility = () => {
      if (document.visibilityState === "visible") refreshSwiper();
    };

    window.addEventListener("pageshow", refreshSwiper);
    document.addEventListener("visibilitychange", onVisibility);

    return () => {
      window.removeEventListener("pageshow", refreshSwiper);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, []);

  // Đặt sau hooks để không vi phạm rules of hooks.
  if (topMovies.length === 0) return null;

  return (
    <div className="w-full max-w-[1900px] px-4 md:px-[50px] mx-auto relative mb-10 3xl:max-w-[2400px] 4xl:max-w-[3200px] 3xl:px-[80px] 4xl:px-[120px]">
      {/* Header */}
      <div className="flex items-center justify-start gap-4 relative min-h-[44px] mb-5">
        <h2 className="text-[2rem] leading-[1.4] font-semibold m-0 text-gray-900 dark:text-[#ffebc6] drop-shadow-[0_2px_1px_rgba(0,0,0,0.3)]">
          Top 10 Phim Bộ Hôm Nay
        </h2>
      </div>

      {/* Nội dung Carousel */}
      <div className="relative top-up group">
        {isMounted ? (
          <>
            {/* Nút điều hướng - Ẩn mặc định, hiện khi hover */}
            <div className="absolute inset-y-0 w-full flex items-center justify-between z-10 pointer-events-none">
              {/* Nút Prev */}
              <button
                id="top-series-prev"
                className="pointer-events-auto p-1.5 bg-transparent text-white opacity-0 group-hover:opacity-50 !opacity-100 transition-opacity -translate-x-full disabled:hidden"
                aria-label="Previous slide"
              >
                <svg className="w-12 h-12" fill="none" viewBox="0 0 16 16">
                  <path d="M10.3335 12.6667L5.66683 8.00004L10.3335 3.33337" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" />
                </svg>
              </button>

              {/* Nút Next */}
              <button
                id="top-series-next"
                className="pointer-events-auto p-1.5 bg-transparent text-white opacity-0 group-hover:opacity-50 hover:!opacity-100 transition-opacity translate-x-full disabled:hidden"
                aria-label="Next slide"
              >
                <svg className="w-12 h-12" fill="none" viewBox="0 0 16 16">
                  <path d="M5.66675 3.33341L10.3334 8.00008L5.66675 12.6667" stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" />
                </svg>
              </button>
            </div>

            {/* Swiper Slider */}
            <Swiper
              modules={[Navigation]}
              onSwiper={(swiper) => {
                swiperRef.current = swiper;
                // Ensure correct slide widths on first mount too.
                requestAnimationFrame(() => {
                  if (!swiper.destroyed) {
                    if (typeof swiper.setBreakpoint === "function") swiper.setBreakpoint();
                    swiper.update();
                  }
                });
              }}
              observer
              observeParents
              updateOnWindowResize
              navigation={{
                prevEl: "#top-series-prev",
                nextEl: "#top-series-next",
              }}
              spaceBetween={16}
              slidesPerView={2} // Mặc định mobile 2 cột
              breakpoints={{
                640: { slidesPerView: 3 },
                1024: { slidesPerView: 5 },
                1440: { slidesPerView: 6 },
                1920: { slidesPerView: 8 },
                2560: { slidesPerView: 10 },
                3200: { slidesPerView: 12 },
              }}
              className="!px-1" // Thêm chút padding để đổ bóng không bị lẹm
            >
              {topMovies.map((movie, index) => (
                <SwiperSlide key={index}>
                  <TopSeriesCard index={index} movie={movie} priority={index < 2} />
                </SwiperSlide>
              ))}
            </Swiper>
          </>
        ) : (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5 2xl:grid-cols-6">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="space-y-3">
                <div className="aspect-[2/3] rounded-xl bg-white/5 animate-pulse" />
                <div className="h-4 w-4/5 rounded bg-white/10 animate-pulse" />
                <div className="h-3 w-3/5 rounded bg-white/10 animate-pulse" />
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};