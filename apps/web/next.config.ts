import type { NextConfig } from "next";
import {
  parseRemoteImageOrigin,
  type RemoteImagePattern,
} from "./lib/site-url";

const apiOrigin = process.env.API_SERVER_URL ?? "http://localhost:8888";
const developmentOssPattern: RemoteImagePattern = {
  protocol: "https",
  hostname: "chtholly-hub-dev.oss-cn-beijing.aliyuncs.com",
  port: "",
  pathname: "/**",
};
const configuredOssPattern = parseRemoteImageOrigin(
  process.env.NEXT_PUBLIC_OSS_PUBLIC_URL,
);
const remotePatterns = [developmentOssPattern, configuredOssPattern]
  .filter((pattern): pattern is RemoteImagePattern => pattern !== null)
  .filter(
    (pattern, index, patterns) =>
      patterns.findIndex(
        (candidate) =>
          candidate.protocol === pattern.protocol &&
          candidate.hostname === pattern.hostname &&
          candidate.port === pattern.port &&
          candidate.pathname === pattern.pathname,
      ) === index,
  );

const nextConfig: NextConfig = {
  output: "standalone",
  devIndicators: false,
  serverExternalPackages: ["pixi.js", "pixi-live2d-display"],
  images: {
    remotePatterns,
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
