import { getTranslations, getLocale } from "next-intl/server";
import { McHeading } from "@/components/mc/McHeading";
import { MODULES } from "@/content/features";
import type { Loc } from "@/content/glossary";

export async function ModuleSection() {
  const t = await getTranslations("modules");
  const loc = (await getLocale()) as Loc;

  const aux = MODULES.filter((m) => m.tierKey === "aux");
  const spine = MODULES.filter((m) => m.tierKey === "spine");
  const base = MODULES.filter((m) => m.tierKey === "base");

  const tierLabel = {
    aux: aux[0]?.tier[loc] ?? "",
    spine: spine[0]?.tier[loc] ?? "",
    base: base[0]?.tier[loc] ?? "",
  };

  return (
    <section className="reveal mx-auto max-w-5xl px-4 py-20">
      <div className="text-center">
        <McHeading as="h2" className="text-white">
          {t("title")}
        </McHeading>
        <p className="mx-auto mt-4 max-w-2xl text-sm text-stone-400">{t("subtitle")}</p>
      </div>

      {/* 地基岩层剖面：顶=可拆卸浮空块，中=设计骨架实心方块，底=平台基岩 */}
      <div className="mx-auto mt-14 max-w-3xl">
        {/* 顶层：可拆卸辅助（浮空、虚线、可飘） */}
        <p className="mb-2 text-center font-pixel text-[0.55rem] text-diamond-400 text-shadow-mc-sm">
          {tierLabel.aux}
        </p>
        <div className="flex flex-wrap justify-center gap-2">
          {aux.map((m) => (
            <div
              key={m.name}
              className="border-2 border-dashed border-diamond-500/60 bg-diamond-500/10 px-3 py-2"
            >
              <span className="font-mono text-xs font-bold text-diamond-400">{m.name}</span>
            </div>
          ))}
        </div>

        {/* 连接缝隙 */}
        <div className="my-3 flex justify-center">
          <span className="font-pixel text-stone-600">· · ·</span>
        </div>

        {/* 中层：设计骨架（3 块实心方块并排，恒为 3） */}
        <p className="mb-2 text-center font-pixel text-[0.55rem] text-grass-300 text-shadow-mc-sm">
          {tierLabel.spine}
        </p>
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
          {spine.map((m) => (
            <div
              key={m.name}
              className="border-4 border-black bg-grass-700/25 p-4 shadow-[inset_2px_2px_0_rgba(255,255,255,0.12),inset_-3px_-3px_0_rgba(0,0,0,0.5)]"
            >
              <p className="font-mono text-sm font-bold text-grass-300">{m.name}</p>
              <p className="mt-2 text-xs leading-relaxed text-stone-300">{m.duty[loc]}</p>
            </div>
          ))}
        </div>

        {/* 底层：平台基岩（横贯、最沉、纹理感） */}
        <p className="mb-2 mt-3 text-center font-pixel text-[0.55rem] text-dirt-500 text-shadow-mc-sm">
          {tierLabel.base}
        </p>
        {base.map((m) => (
          <div
            key={m.name}
            className="border-4 border-black bg-dirt-600/40 p-4 shadow-[inset_2px_2px_0_rgba(255,255,255,0.08),inset_-3px_-4px_0_rgba(0,0,0,0.55)]"
            style={{
              backgroundImage:
                "repeating-linear-gradient(90deg, rgba(0,0,0,0.18) 0 2px, transparent 2px 22px)",
            }}
          >
            <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:gap-4">
              <span className="font-mono text-sm font-bold text-dirt-500 sm:shrink-0">
                {m.name}
              </span>
              <span className="text-xs leading-relaxed text-stone-300">{m.duty[loc]}</span>
            </div>
          </div>
        ))}
      </div>

      <p className="mx-auto mt-8 max-w-2xl text-center text-xs leading-relaxed text-stone-500">
        {loc === "zh"
          ? "判据：删了顶层辅助，中层与基岩照样编译运行；但抽掉基岩，client 就塌了。"
          : loc === "ja"
            ? "判定基準：上層の補助を消しても中層と基岩はコンパイル・実行できますが、基岩を抜くと client は崩れます。"
            : "The test: remove the top aux and the spine + bedrock still build; pull the bedrock and client collapses."}
      </p>
    </section>
  );
}
