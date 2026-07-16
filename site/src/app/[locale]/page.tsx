import { setRequestLocale } from "next-intl/server";
import { Hero } from "@/components/home/Hero";
import { WhatSection } from "@/components/home/WhatSection";
import { FeatureSection } from "@/components/home/FeatureSection";
import { SecuritySection } from "@/components/home/SecuritySection";
import { CapabilitySection } from "@/components/home/CapabilitySection";
import { ModuleSection } from "@/components/home/ModuleSection";
import { TechCtaSection } from "@/components/home/TechCtaSection";

export default async function HomePage({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  setRequestLocale(locale);

  return (
    <>
      <Hero />
      <WhatSection />
      <FeatureSection />
      <SecuritySection />
      <CapabilitySection />
      <ModuleSection />
      <TechCtaSection />
    </>
  );
}
