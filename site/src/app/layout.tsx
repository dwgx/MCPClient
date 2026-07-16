import type { ReactNode } from "react";

// 根 layout 只做透传；真正的 html/body 在 [locale]/layout.tsx 里，
// 以便根据 locale 设置 lang 属性。
export default function RootLayout({ children }: { children: ReactNode }) {
  return children;
}
