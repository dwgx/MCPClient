"use client";

import { useState, useRef, useEffect } from "react";
import { useLocale } from "next-intl";
import { usePathname, useRouter } from "@/i18n/routing";
import type { Locale } from "@/i18n/routing";

const LABELS: Record<Locale, string> = {
  zh: "中文",
  en: "English",
  ja: "日本語",
};

const ORDER: Locale[] = ["zh", "en", "ja"];

export function LanguageSwitcher() {
  const locale = useLocale() as Locale;
  const pathname = usePathname();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function onClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, []);

  function switchTo(next: Locale) {
    setOpen(false);
    if (next !== locale) router.replace(pathname, { locale: next });
  }

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen((v) => !v)}
        aria-label="Language"
        className="font-pixel border-2 border-black bg-stone-700 px-2 py-1.5 text-[0.55rem] text-stone-200 transition-colors hover:text-grass-300"
      >
        {LABELS[locale]}
      </button>
      {open && (
        <div className="absolute right-0 z-50 mt-2 w-28 border-2 border-black bg-stone-700 shadow-lg">
          {ORDER.map((l) => (
            <button
              key={l}
              onClick={() => switchTo(l)}
              className={`block w-full px-3 py-2 text-left text-xs transition-colors hover:bg-stone-600 ${
                l === locale ? "text-grass-300" : "text-stone-200"
              }`}
            >
              {LABELS[l]}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
