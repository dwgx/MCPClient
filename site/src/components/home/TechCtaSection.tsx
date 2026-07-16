import { getTranslations, getLocale } from "next-intl/server";
import { McHeading } from "@/components/mc/McHeading";
import { McButton } from "@/components/mc/McButton";
import { TECH_FACTS } from "@/content/features";
import type { Loc } from "@/content/glossary";

export async function TechCtaSection() {
  const tTech = await getTranslations("tech");
  const tCta = await getTranslations("cta");
  const loc = (await getLocale()) as Loc;

  return (
    <section className="reveal border-t-4 border-black bg-stone-900 py-20">
      <div className="mx-auto max-w-5xl px-4">
        <McHeading as="h3" className="text-center text-white">
          {tTech("title")}
        </McHeading>

        <div className="mt-10 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {TECH_FACTS.map((f) => (
            <div
              key={f.value}
              className="border-2 border-black bg-stone-800 p-4 shadow-[inset_2px_2px_0_rgba(0,0,0,0.5)]"
            >
              <p className="font-pixel text-[0.55rem] text-stone-500">{f.label[loc]}</p>
              <p className="mt-2 font-mono text-sm text-grass-300">{f.value}</p>
            </div>
          ))}
        </div>

        {/* CTA */}
        <div className="mt-16 border-4 border-black bg-grass-700/20 p-8 text-center shadow-[inset_2px_2px_0_rgba(255,255,255,0.1),inset_-2px_-2px_0_rgba(0,0,0,0.5)]">
          <McHeading as="h3" className="text-white">
            {tCta("title")}
          </McHeading>
          <p className="mx-auto mt-4 max-w-xl text-sm leading-relaxed text-stone-300">
            {tCta("body")}
          </p>
          <div className="mt-8">
            <McButton href="/docs" variant="primary">
              {tCta("button")}
            </McButton>
          </div>
        </div>
      </div>
    </section>
  );
}
