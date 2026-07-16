import { getTranslations } from "next-intl/server";
import { Link } from "@/i18n/routing";
import { GITHUB_URL } from "@/lib/site";
import { PixelIcon } from "@/components/mc/PixelIcon";
import { LanguageSwitcher } from "./LanguageSwitcher";

export async function SiteHeader() {
  const t = await getTranslations("nav");

  return (
    <header className="mc-topbar sticky top-0 z-50 border-b-4 border-black">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4">
        <Link href="/" className="flex items-center gap-2 sm:gap-3">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center border-2 border-black bg-stone-800">
            <PixelIcon name="grass" size={22} />
          </span>
          <span className="font-pixel text-xs text-white text-shadow-mc-sm sm:text-sm">
            MCPClient
          </span>
        </Link>

        <nav className="flex items-center gap-3 sm:gap-6">
          <Link
            href="/docs"
            className="font-pixel text-[0.6rem] text-stone-200 transition-colors hover:text-grass-300"
          >
            {t("docs")}
          </Link>
          <Link
            href="/#capabilities"
            className="hidden font-pixel text-[0.6rem] text-stone-200 transition-colors hover:text-grass-300 sm:inline"
          >
            {t("capabilities")}
          </Link>
          <Link
            href="/#security"
            className="hidden font-pixel text-[0.6rem] text-stone-200 transition-colors hover:text-grass-300 sm:inline"
          >
            {t("security")}
          </Link>
          <a
            href={GITHUB_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="hidden font-pixel text-[0.6rem] text-stone-200 transition-colors hover:text-grass-300 xs:inline sm:inline"
          >
            GitHub
          </a>
          <LanguageSwitcher />
        </nav>
      </div>
    </header>
  );
}
