import { getTranslations, getLocale } from "next-intl/server";
import { McHeading } from "@/components/mc/McHeading";
import { McPanel } from "@/components/mc/McPanel";
import { PixelIcon } from "@/components/mc/PixelIcon";
import { FEATURES } from "@/content/features";
import type { Loc } from "@/content/glossary";

export async function FeatureSection() {
  const t = await getTranslations("features");
  const loc = (await getLocale()) as Loc;

  return (
    <section id="features" className="reveal border-y-4 border-black bg-stone-900 py-20">
      <div className="mx-auto max-w-6xl px-4">
        <div className="text-center">
          <McHeading as="h2" className="text-white">
            {t("title")}
          </McHeading>
          <p className="mt-4 text-sm text-stone-400">{t("subtitle")}</p>
        </div>

        <div className="mt-12 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map((f) => (
            <McPanel key={f.id} hover>
              <div className="flex items-center gap-3">
                <span className="flex h-11 w-11 shrink-0 items-center justify-center border-2 border-black bg-stone-800 shadow-[inset_2px_2px_0_rgba(255,255,255,0.12),inset_-2px_-2px_0_rgba(0,0,0,0.5)]">
                  <PixelIcon name={f.icon} size={28} />
                </span>
                <h3 className="font-pixel text-xs leading-snug text-white text-shadow-mc-sm">
                  {f.title[loc]}
                </h3>
              </div>
              <p className="mt-4 text-sm leading-relaxed text-stone-300">
                {f.body[loc]}
              </p>
            </McPanel>
          ))}
        </div>
      </div>
    </section>
  );
}
