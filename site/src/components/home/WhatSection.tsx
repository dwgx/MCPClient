import { getTranslations } from "next-intl/server";
import { McHeading } from "@/components/mc/McHeading";
import { PixelIcon, type IconName } from "@/components/mc/PixelIcon";

export async function WhatSection() {
  const t = await getTranslations("what");

  const flow: { key: string; icon: IconName; accent: string }[] = [
    { key: "flowLlm", icon: "endereye", accent: "border-t-diamond-500" },
    { key: "flowMcp", icon: "repeater", accent: "border-t-danger-500" },
    { key: "flowKernel", icon: "shield", accent: "border-t-gold-400" },
    { key: "flowJvm", icon: "grass", accent: "border-t-grass-500" },
  ];

  return (
    <section className="reveal mx-auto max-w-5xl px-4 py-20">
      <McHeading as="h2" className="text-center text-white">
        {t("title")}
      </McHeading>

      <p className="mx-auto mt-8 max-w-3xl text-center text-base leading-relaxed text-stone-300">
        {t("body")}
      </p>

      {/* 数据管道流向：LLM → MCP → 内核 → JVM */}
      <div className="mt-14 flex flex-col items-stretch justify-center gap-0 md:flex-row md:items-center">
        {flow.map((node, i) => (
          <div key={node.key} className="flex flex-col items-center md:flex-row">
            <div
              className={`group relative flex w-44 flex-col items-center gap-3 border-4 border-t-8 border-black ${node.accent} bg-stone-700 px-4 py-5 shadow-[inset_2px_2px_0_rgba(255,255,255,0.12),inset_-3px_-3px_0_rgba(0,0,0,0.5)]`}
            >
              <div className="flex h-14 w-14 items-center justify-center border-2 border-black bg-stone-800 shadow-[inset_2px_2px_0_rgba(0,0,0,0.5)]">
                <PixelIcon name={node.icon} size={40} />
              </div>
              <span className="font-pixel text-center text-[0.6rem] leading-snug text-white text-shadow-mc-sm">
                {t(node.key)}
              </span>
            </div>

            {i < flow.length - 1 && (
              <div className="flex items-center justify-center py-1 md:px-1 md:py-0">
                {/* 竖屏用向下箭头，横屏用向右管道 */}
                <span className="font-pixel text-lg text-grass-300 md:hidden">▼</span>
                <div className="hidden items-center md:flex">
                  <span className="block h-1.5 w-4 bg-grass-500" />
                  <span className="font-pixel text-grass-300">▶</span>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </section>
  );
}
