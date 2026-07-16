import type { ReactNode } from "react";
import { setRequestLocale } from "next-intl/server";
import { DocSidebar } from "@/components/docs/DocSidebar";
import { DocSearch } from "@/components/docs/DocSearch";
import { buildSearchIndex } from "@/content/docs";
import type { Loc } from "@/content/glossary";

export default async function DocsLayout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  setRequestLocale(locale);
  const loc = locale as Loc;
  const searchIndex = buildSearchIndex(loc);

  return (
    <div className="mx-auto flex max-w-7xl gap-8 px-4 py-10">
      {/* 左侧：搜索 + 导航 */}
      <aside className="hidden w-60 shrink-0 lg:block">
        <div className="sticky top-24 space-y-6">
          <DocSearch index={searchIndex} />
          <DocSidebar />
        </div>
      </aside>

      {/* 内容区 */}
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
}
