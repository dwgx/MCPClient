import { getTranslations } from "next-intl/server";
import type { DocBlock } from "@/content/docs/types";
import type { Loc } from "@/content/glossary";

export async function DocToc({ blocks, loc }: { blocks: DocBlock[]; loc: Loc }) {
  const t = await getTranslations("docs");
  const headings = blocks.filter(
    (b): b is Extract<DocBlock, { type: "h2" | "h3" }> =>
      b.type === "h2" || b.type === "h3"
  );

  if (headings.length === 0) return null;

  return (
    <div className="sticky top-24">
      <p className="font-pixel mb-3 text-[0.6rem] text-stone-500 text-shadow-mc-sm">
        {t("onThisPage")}
      </p>
      <ul className="space-y-2 border-l-2 border-stone-700">
        {headings.map((h) => (
          <li key={h.id} className={h.type === "h3" ? "pl-6" : "pl-3"}>
            <a
              href={`#${h.id}`}
              className="text-xs text-stone-400 transition-colors hover:text-grass-300"
            >
              {h.text[loc]}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
