"use client";

import { useState } from "react";
import { useLocale } from "next-intl";
import { PixelIcon, type IconName } from "@/components/mc/PixelIcon";
import { CAPABILITIES } from "@/content/kernel";
import type { Loc } from "@/content/glossary";

// 每个能力包配一个 MC 物品意象图标
const CAP_ICON: Record<string, IconName> = {
  C1: "search", // 自省
  C2: "observer", // 被动观察
  C3: "cursor", // 拦截
  C4: "repeater", // 热重定义
  C5: "lock", // 深访问
  C6: "observer", // JVMTI 调试器
  C7: "quill", // 合成
  C8: "shield", // 接缝
  C9: "endereye", // GUI
};

export function CapabilityGrid() {
  const loc = useLocale() as Loc;
  const [active, setActive] = useState<string | null>(null);

  const current = CAPABILITIES.find((c) => c.id === active);

  return (
    <div className="mt-12 flex flex-col items-center">
      {/* 物品栏格子：9 格一排（正好 C1-C9，像 MC 快捷栏） */}
      <div className="inline-flex flex-wrap justify-center gap-2 border-4 border-black bg-stone-700 p-3 shadow-[inset_3px_3px_0_rgba(0,0,0,0.4),inset_-3px_-3px_0_rgba(255,255,255,0.08)]">
        {CAPABILITIES.map((c) => {
          const on = active === c.id;
          return (
            <button
              key={c.id}
              onMouseEnter={() => setActive(c.id)}
              onFocus={() => setActive(c.id)}
              onClick={() => setActive(on ? null : c.id)}
              className={`group relative flex h-[4.5rem] w-[4.5rem] flex-col items-center justify-center gap-1 border-2 transition-colors ${
                on
                  ? "border-grass-300 bg-stone-500"
                  : "border-stone-900 bg-stone-800 hover:bg-stone-600"
              } shadow-[inset_2px_2px_0_rgba(0,0,0,0.45),inset_-2px_-2px_0_rgba(255,255,255,0.12)]`}
            >
              <PixelIcon name={CAP_ICON[c.id]} size={32} />
              <span className="font-pixel text-[0.5rem] text-grass-300 text-shadow-mc-sm">
                {c.id}
              </span>
            </button>
          );
        })}
      </div>

      {/* MC 物品提示框 */}
      <div className="mt-6 min-h-[6rem] w-full max-w-xl">
        {current ? (
          <div className="border-2 border-arcane-500 bg-stone-950/95 p-4 shadow-[0_0_0_2px_#0d0d0e]">
            <div className="flex items-baseline gap-2">
              <span className="font-pixel text-sm text-grass-300 text-shadow-mc-sm">
                {current.id}
              </span>
              <span className="text-sm font-bold text-white">{current.name[loc]}</span>
            </div>
            <p className="mt-2 text-sm leading-relaxed text-stone-300">{current.duty[loc]}</p>
            <p className="mt-2 font-mono text-[0.7rem] text-stone-500">{current.pkg}</p>
          </div>
        ) : (
          <p className="pt-6 text-center text-xs text-stone-500">
            {loc === "zh"
              ? "悬停或点击格子查看能力详情"
              : loc === "ja"
                ? "マスにカーソルを合わせるかクリックして詳細を表示"
                : "Hover or tap a slot to see details"}
          </p>
        )}
      </div>
    </div>
  );
}
