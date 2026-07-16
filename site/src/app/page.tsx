import type { Metadata } from "next";
import { routing } from "@/i18n/routing";

// 静态导出下没有中间件做语言重定向，所以根路径 `/` 自带一个重定向页：
// meta refresh + 内联脚本，跳到默认语言。用相对路径 `zh/`，自动适配 basePath（/MCPClient/）。
const target = `${routing.defaultLocale}/`;

export const metadata: Metadata = {
  robots: { index: false, follow: false },
};

export default function RootRedirect() {
  const script = `location.replace(location.pathname.replace(/\\/$/, '') + '/${routing.defaultLocale}/' + location.search + location.hash)`;
  return (
    <html lang={routing.defaultLocale}>
      <head>
        <meta httpEquiv="refresh" content={`0; url=${target}`} />
        <script dangerouslySetInnerHTML={{ __html: script }} />
      </head>
      <body style={{ background: "#1a1a1d", color: "#e0d0d0", fontFamily: "sans-serif" }}>
        <noscript>
          <a href={target} style={{ color: "#63c74d" }}>
            MCPClient →
          </a>
        </noscript>
      </body>
    </html>
  );
}
