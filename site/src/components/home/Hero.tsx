import { getTranslations } from "next-intl/server";
import { McButton } from "@/components/mc/McButton";
import { GITHUB_URL } from "@/lib/site";

export async function Hero() {
  const t = await getTranslations("hero");

  return (
    <section className="relative overflow-hidden border-b-4 border-black">
      {/* 静态像素网格背景（无动效，老 Mojang 是朴实静态的） */}
      <div className="hero-grid absolute inset-0 opacity-30" />
      <div className="absolute inset-0 bg-gradient-to-b from-stone-900/30 via-stone-800/60 to-stone-800" />

      <div className="relative mx-auto w-full max-w-4xl px-4 py-24 text-center sm:py-32">
        <span className="font-pixel inline-block border-2 border-black bg-stone-700 px-3 py-2 text-[0.55rem] text-grass-300 text-shadow-mc-sm sm:text-[0.6rem]">
          {t("badge")}
        </span>

        <h1 className="font-pixel hero-title mt-8 text-white text-shadow-mc">
          {t("title")}
        </h1>

        <p className="mx-auto mt-8 max-w-2xl break-words text-base leading-relaxed text-stone-200 sm:text-lg">
          {t("slogan")}
        </p>

        <p className="mx-auto mt-4 max-w-2xl break-words text-sm leading-relaxed text-stone-400">
          {t("intro")}
        </p>

        <div className="mt-10 flex flex-wrap items-center justify-center gap-4">
          <McButton href="/docs" variant="primary">
            {t("ctaDocs")}
          </McButton>
          <McButton href={GITHUB_URL} variant="secondary" external>
            {t("ctaGithub")}
          </McButton>
        </div>
      </div>

      {/* Minecraft 像素地平线剪影：草地条 + 泥土带 */}
      <div className="relative" aria-hidden="true">
        <div className="h-3 w-full bg-grass-500 shadow-[inset_0_2px_0_rgba(255,255,255,0.2)]" />
        <div
          className="h-6 w-full bg-dirt-600"
          style={{
            backgroundImage:
              "repeating-linear-gradient(90deg, rgba(0,0,0,0.22) 0 3px, transparent 3px 28px)",
          }}
        />
      </div>
    </section>
  );
}
