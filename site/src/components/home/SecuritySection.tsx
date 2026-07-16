import { getTranslations, getLocale } from "next-intl/server";
import { McHeading, McTag } from "@/components/mc/McHeading";
import { KERNEL_LAYERS } from "@/content/kernel";
import type { Loc } from "@/content/glossary";

export async function SecuritySection() {
  const t = await getTranslations("security");
  const loc = (await getLocale()) as Loc;

  const statusLabel: Record<string, string> = {
    live: t("statusLive"),
    "opt-in": t("statusOptin"),
    built: t("statusBuilt"),
  };
  const statusColor: Record<string, "grass" | "gold" | "stone"> = {
    live: "grass",
    "opt-in": "gold",
    built: "stone",
  };

  // 7 层从外到内的颜色（越内越危险 → 越暖）
  const ringColor = [
    "bg-diamond-500/15 border-diamond-500",
    "bg-diamond-500/20 border-diamond-500",
    "bg-grass-500/20 border-grass-500",
    "bg-grass-500/25 border-grass-400",
    "bg-gold-400/20 border-gold-400",
    "bg-danger-500/20 border-danger-500",
    "bg-danger-500/30 border-danger-500",
  ];

  return (
    <section id="security" className="reveal border-y-4 border-black bg-stone-900 py-20">
      <div className="mx-auto max-w-6xl px-4">
        <div className="text-center">
          <McHeading as="h2" className="text-white">
            {t("title")}
          </McHeading>
          <p className="mx-auto mt-4 max-w-2xl text-sm text-stone-400">{t("subtitle")}</p>
        </div>

        <div className="mt-14 grid items-center gap-10 lg:grid-cols-2">
          {/* 左：同心城墙图 —— 7 道墙包住核心 JVM */}
          <div className="flex justify-center">
            <div className="relative aspect-square w-full max-w-md">
              {KERNEL_LAYERS.map((layer, i) => {
                const inset = i * 6.7; // 每层向内收 6.7%
                return (
                  <div
                    key={layer.id}
                    className={`absolute flex items-start justify-center border-2 ${ringColor[i]} shadow-[inset_2px_2px_0_rgba(0,0,0,0.35)]`}
                    style={{
                      top: `${inset}%`,
                      left: `${inset}%`,
                      right: `${inset}%`,
                      bottom: `${inset}%`,
                    }}
                  >
                    <span className="mt-1 font-pixel text-[0.5rem] text-white text-shadow-mc-sm">
                      {layer.id}
                    </span>
                  </div>
                );
              })}
              {/* 核心 JVM */}
              <div className="absolute inset-[46.9%] flex items-center justify-center border-2 border-black bg-stone-950">
                <span className="font-pixel text-[0.45rem] text-grass-300">JVM</span>
              </div>
            </div>
          </div>

          {/* 右：逐层详情 */}
          <div className="space-y-2">
            {KERNEL_LAYERS.map((layer) => (
              <div
                key={layer.id}
                className="group border-2 border-black bg-stone-800 p-3 shadow-[inset_2px_2px_0_rgba(0,0,0,0.4)] transition-transform hover:translate-x-1"
              >
                <div className="flex items-center gap-3">
                  <span className="font-pixel flex h-7 w-9 shrink-0 items-center justify-center border-2 border-black bg-grass-600 text-[0.6rem] text-white text-shadow-mc-sm">
                    {layer.id}
                  </span>
                  <span className="font-pixel flex-1 text-[0.6rem] leading-snug text-white text-shadow-mc-sm">
                    {layer.name[loc]}
                  </span>
                  <McTag color={statusColor[layer.status]}>{statusLabel[layer.status]}</McTag>
                </div>
                <p className="mt-2 text-xs leading-relaxed text-stone-400">{layer.duty[loc]}</p>
              </div>
            ))}
          </div>
        </div>

        <p className="mx-auto mt-10 max-w-3xl text-center text-xs leading-relaxed text-stone-500">
          {t("note")}
        </p>
      </div>
    </section>
  );
}
