import { setRequestLocale } from "next-intl/server";
import { routing } from "@/i18n/routing";

export function generateStaticParams() {
  return routing.locales.map((locale) => ({ locale }));
}

// 静态导出：/[locale]/docs 客户端重定向到 overview（相对跳转，自动带 basePath）
export default async function DocsIndexPage({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  setRequestLocale(locale);
  const script =
    "location.replace(location.pathname.replace(/\\/$/, '') + '/overview/' + location.search + location.hash)";
  return (
    <>
      <meta httpEquiv="refresh" content="0; url=overview/" />
      <script dangerouslySetInnerHTML={{ __html: script }} />
    </>
  );
}
