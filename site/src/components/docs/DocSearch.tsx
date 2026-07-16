"use client";

import { useMemo, useState } from "react";
import Fuse from "fuse.js";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { PixelIcon } from "@/components/mc/PixelIcon";
import type { SearchDoc } from "@/content/docs";

export function DocSearch({ index }: { index: SearchDoc[] }) {
  const t = useTranslations("docs");
  const [query, setQuery] = useState("");

  const fuse = useMemo(
    () =>
      new Fuse(index, {
        keys: ["title", "description", "text"],
        threshold: 0.4,
        ignoreLocation: true,
        minMatchCharLength: 2,
      }),
    [index]
  );

  const results = query.trim() ? fuse.search(query.trim()).slice(0, 8) : [];

  return (
    <div className="relative">
      <div className="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2">
        <PixelIcon name="search" size={18} />
      </div>
      <input
        type="search"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={t("searchPlaceholder")}
        className="w-full border-2 border-black bg-stone-900 py-2 pl-8 pr-3 text-sm text-white placeholder:text-stone-500 shadow-[inset_2px_2px_0_rgba(0,0,0,0.5)] focus:outline-none focus:ring-2 focus:ring-grass-500"
      />

      {query.trim() && (
        <div className="absolute z-20 mt-2 w-full border-2 border-black bg-stone-700 shadow-lg">
          {results.length === 0 ? (
            <p className="px-3 py-3 text-xs text-stone-400">{t("noResults")}</p>
          ) : (
            <ul>
              {results.map((r) => (
                <li key={r.item.slug}>
                  <Link
                    href={`/docs/${r.item.slug}`}
                    onClick={() => setQuery("")}
                    className="block border-b border-black/40 px-3 py-2 hover:bg-stone-600"
                  >
                    <span className="text-sm font-bold text-grass-300">
                      {r.item.title}
                    </span>
                    <span className="mt-0.5 block truncate text-xs text-stone-400">
                      {r.item.description}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
