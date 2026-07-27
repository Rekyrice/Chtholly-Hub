import type { Metadata } from "next";
import { siteConfig } from "@/lib/site.config";
import { siteUrl } from "@/lib/site-url";
import { MOTION_BOOTSTRAP_SCRIPT } from "@/lib/motion-bootstrap";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: siteUrl,
  title: {
    default: siteConfig.name,
    template: `%s | ${siteConfig.name}`,
  },
  description: siteConfig.description,
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const cssVars = [
    `--blog-primary: ${siteConfig.theme.primary}`,
    `--blog-body-bg: ${siteConfig.theme.bodyBg}`,
    `--font-noto-sans-sc: "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif`,
    `--font-source-sans: "Segoe UI", Arial, sans-serif`,
    `--font-serif: "Noto Serif SC", "Songti SC", Georgia, serif`,
    `--font-jp: "Hiragino Sans", "Yu Gothic", Meiryo, sans-serif`,
    `--font-mono: "JetBrains Mono", "Cascadia Code", "Fira Code", Consolas, monospace`,
    `--blog-font: var(--font-noto-sans-sc), var(--font-source-sans), sans-serif`,
  ].join("; ");

  return (
    <html
      lang="zh-CN"
      className="h-full antialiased"
      suppressHydrationWarning
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: MOTION_BOOTSTRAP_SCRIPT }} />
      </head>
      <body className="min-h-full flex flex-col" suppressHydrationWarning>
        <style>{`:root { ${cssVars} }`}</style>
        {children}
      </body>
    </html>
  );
}
