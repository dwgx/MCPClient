"use client";

import { useLocale } from "next-intl";
import { usePathname, Link } from "@/i18n/routing";
import { DOC_GROUPS } from "@/content/docs";
import type { Loc } from "@/content/glossary";

export function DocSidebar() {
  const pathname = usePathname();
  const loc = useLocale() as Loc;

  return (
    <nav className="space-y-6">
      {DOC_GROUPS.map((group) => (
        <div key={group.title.en}>
          <p className="font-pixel mb-3 text-[0.6rem] text-stone-500 text-shadow-mc-sm">
            {group.title[loc]}
          </p>
          <ul className="space-y-1">
            {group.pages.map((page) => {
              const href = `/docs/${page.slug}`;
              const active = pathname === href;
              return (
                <li key={page.slug}>
                  <Link
                    href={href}
                    className={`block border-l-4 px-3 py-2 text-sm transition-colors ${
                      active
                        ? "border-l-grass-500 bg-stone-700 text-grass-300"
                        : "border-l-transparent text-stone-300 hover:border-l-stone-500 hover:bg-stone-700/50"
                    }`}
                  >
                    {page.title[loc]}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );
}
