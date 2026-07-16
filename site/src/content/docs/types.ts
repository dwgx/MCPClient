// 文档内容模型：结构化 block，可同时喂渲染器、页内目录（TOC）、搜索索引。
// 三语：可译字段用 L10n（zh/en/ja）；结构字段（id/lang/code）不译。
import type { L10n } from "@/content/glossary";

export type DocBlock =
  | { type: "p"; text: L10n }
  | { type: "h2"; text: L10n; id: string }
  | { type: "h3"; text: L10n; id: string }
  | { type: "code"; lang?: string; code: string } // 代码不译
  | { type: "callout"; tone: "info" | "warn" | "danger"; text: L10n }
  | { type: "list"; items: L10n[] }
  | { type: "table"; head: L10n[]; rows: L10n[][] };

export interface DocPage {
  slug: string; // 路由不译
  title: L10n;
  description: L10n;
  blocks: DocBlock[];
}

export interface DocGroup {
  title: L10n;
  pages: { slug: string; title: L10n }[];
}
