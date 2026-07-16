import type { DocPage, DocGroup } from "./types";
import type { Loc } from "@/content/glossary";
import { overview } from "./overview";
import { architecture } from "./architecture";
import { security } from "./security";
import { capabilities } from "./capabilities";
import { tools } from "./tools";
import { native } from "./native";
import { modules } from "./modules";
import { timeline } from "./timeline";

export const DOC_PAGES: DocPage[] = [
  overview,
  architecture,
  security,
  capabilities,
  tools,
  native,
  modules,
  timeline,
];

const T = (zh: string, en: string, ja: string) => ({ zh, en, ja });

export const DOC_GROUPS: DocGroup[] = [
  {
    title: T("开始", "Getting Started", "はじめに"),
    pages: [
      { slug: "overview", title: T("概览", "Overview", "概要") },
      { slug: "architecture", title: T("架构总览", "Architecture", "アーキテクチャ") },
    ],
  },
  {
    title: T("内核", "Kernel", "カーネル"),
    pages: [
      { slug: "security", title: T("7 层安全内核", "7-Layer Kernel", "7 層カーネル") },
      { slug: "capabilities", title: T("能力包 C1-C9", "Capabilities C1–C9", "能力パック C1–C9") },
      { slug: "tools", title: T("MCP 工具参考", "MCP Tools", "MCP ツール") },
    ],
  },
  {
    title: T("平台", "Platform", "プラットフォーム"),
    pages: [
      { slug: "native", title: T("原生 C6 与构建", "Native C6 & Build", "ネイティブ C6 とビルド") },
      { slug: "modules", title: T("模块与平台 SPI", "Modules & SPI", "モジュールと SPI") },
      { slug: "timeline", title: T("时间轴脊柱", "Timeline Spine", "タイムライン脊柱") },
    ],
  },
];

export function getDocPage(slug: string): DocPage | undefined {
  return DOC_PAGES.find((p) => p.slug === slug);
}

// 供客户端搜索用的扁平索引（按 locale 生成）
export interface SearchDoc {
  slug: string;
  title: string;
  description: string;
  text: string;
}

export function buildSearchIndex(loc: Loc): SearchDoc[] {
  return DOC_PAGES.map((p) => ({
    slug: p.slug,
    title: p.title[loc],
    description: p.description[loc],
    text: p.blocks
      .map((b) => {
        if (b.type === "code") return b.code;
        if (b.type === "list") return b.items.map((i) => i[loc]).join(" ");
        if (b.type === "table")
          return [...b.head, ...b.rows.flat()].map((c) => c[loc]).join(" ");
        if ("text" in b) return b.text[loc];
        return "";
      })
      .join(" "),
  }));
}
