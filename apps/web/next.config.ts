import type { NextConfig } from "next";

const apiOrigin = process.env.API_SERVER_URL ?? "http://localhost:8888";

const nextConfig: NextConfig = {
  output: "standalone",
  serverExternalPackages: ["pixi.js", "pixi-live2d-display"],
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "chtholly-hub-dev.oss-cn-beijing.aliyuncs.com",
        pathname: "/**",
      },
    ],
  },
  async rewrites() {
    // 生产环境由 Nginx 反代 /api；开发时 Node 代理到 Spring Boot
    if (process.env.NODE_ENV === "development") {
      return [
        {
          source: "/api/v1/:path*",
          destination: `${apiOrigin}/api/v1/:path*`,
        },
        {
          source: "/uploads/:path*",
          destination: `${apiOrigin}/uploads/:path*`,
        },
      ];
    }
    return [];
  },
};

export default nextConfig;
