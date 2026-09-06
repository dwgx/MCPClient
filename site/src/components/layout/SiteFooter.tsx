import { getTranslations } from "next-intl/server";
import { Link } from "@/i18n/routing";
import { GITHUB_URL } from "@/lib/site";
import { PixelIcon } from "@/components/mc/PixelIcon";

export async function SiteFooter() {
  const t = await getTranslations("footer");

  return (
    <footer className="border-t-4 border-black bg-stone-900 py-10">
      <div className="mx-auto flex max-w-6xl flex-col gap-6 px-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <span className="flex h-7 w-7 items-center justify-center border-2 border-black bg-stone-800">
            <PixelIcon name="grass" size={18} />
          </span>
          <div>
            <p className="font-pixel text-xs text-white text-shadow-mc-sm">
              MCPClient
            </p>
            <p className="mt-1 text-xs text-stone-400">{t("tagline")}</p>
          </div>
        </div>

        <div className="flex flex-wrap gap-x-6 gap-y-2 text-xs text-stone-300">
          <Link href="/docs" className="hover:text-grass-300">
            {t("docs")}
          </Link>
          <a href={GITHUB_URL} target="_blank" rel="noopener noreferrer" className="hover:text-grass-300">
            GitHub
          </a>
          <a
            href={`${GITHUB_URL}/blob/main/LICENSE`}
            target="_blank"
            rel="noopener noreferrer"
            className="hover:text-grass-300"
          >
            CC BY-NC-ND 4.0
          </a>
        </div>
      </div>

      <p className="mt-8 text-center text-[0.65rem] text-stone-500">
        {t("disclaimer")}
      </p>
    </footer>
  );
}
