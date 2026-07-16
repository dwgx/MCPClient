import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

const withNextIntl = createNextIntlPlugin("./src/i18n/request.ts");

// GitHub Pages 项目页部署到 /MCPClient 子路径。
// 本地 dev（NODE_ENV=development）不加 basePath，方便直接访问 localhost:3000。
const isProd = process.env.NODE_ENV === "production";
const basePath = isProd ? "/MCPClient" : "";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  output: "export", // 静态导出到 out/
  basePath,
  images: { unoptimized: true }, // 静态托管无图片优化服务器
  trailingSlash: true, // GitHub Pages 对 /path/ 更友好
  env: {
    NEXT_PUBLIC_BASE_PATH: basePath,
  },
};

export default withNextIntl(nextConfig);
