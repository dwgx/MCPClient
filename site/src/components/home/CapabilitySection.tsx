import { getTranslations } from "next-intl/server";
import { McHeading } from "@/components/mc/McHeading";
import { CapabilityGrid } from "./CapabilityGrid";

export async function CapabilitySection() {
  const t = await getTranslations("capabilities");

  return (
    <section id="capabilities" className="reveal mx-auto max-w-6xl px-4 py-20">
      <div className="text-center">
        <McHeading as="h2" className="text-white">
          {t("title")}
        </McHeading>
        <p className="mt-4 text-sm text-stone-400">{t("subtitle")}</p>
      </div>
      <CapabilityGrid />
    </section>
  );
}
