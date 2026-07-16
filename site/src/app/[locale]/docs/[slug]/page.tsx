import { notFound } from "next/navigation";
import type { Metadata } from "next";
import { setRequestLocale } from "next-intl/server";
import { routing } from "@/i18n/routing";
import type { Loc } from "@/content/glossary";
import { DOC_PAGES, getDocPage } from "@/content/docs";
import { DocRenderer } from "@/components/docs/DocRenderer";
import { DocToc } from "@/components/docs/DocToc";
import { McHeading } from "@/components/mc/McHeading";

export function generateStaticParams() {
  return routing.locales.flatMap((locale) =>
    DOC_PAGES.map((p) => ({ locale, slug: p.slug }))
  );
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string; slug: string }>;
}): Promise<Metadata> {
  const { locale, slug } = await params;
  const page = getDocPage(slug);
  if (!page) return {};
  const loc = locale as Loc;
  return {
    title: `${page.title[loc]} · MCPClient`,
    description: page.description[loc],
  };
}

export default async function DocPage({
  params,
}: {
  params: Promise<{ locale: string; slug: string }>;
}) {
  const { locale, slug } = await params;
  setRequestLocale(locale);
  const loc = locale as Loc;

  const page = getDocPage(slug);
  if (!page) notFound();

  return (
    <div className="flex gap-8">
      <article className="min-w-0 flex-1">
        <McHeading as="h1" className="text-white">
          {page.title[loc]}
        </McHeading>
        <p className="mt-4 text-sm text-stone-400">{page.description[loc]}</p>
        <div className="mt-8">
          <DocRenderer blocks={page.blocks} loc={loc} />
        </div>
      </article>

      <aside className="hidden w-52 shrink-0 xl:block">
        <DocToc blocks={page.blocks} loc={loc} />
      </aside>
    </div>
  );
}
