import type { Metadata } from "next";
import { Noto_Sans_SC, Source_Sans_3 } from "next/font/google";
import { siteConfig } from "@/lib/site.config";
import "./globals.css";

const notoSansSc = Noto_Sans_SC({
  subsets: ["latin"],
  weight: ["300", "400", "500", "700"],
  variable: "--font-noto-sans-sc",
});

const sourceSans = Source_Sans_3({
  subsets: ["latin"],
  weight: ["300", "400", "600", "700"],
  variable: "--font-source-sans",
});

export const metadata: Metadata = {
  title: siteConfig.name,
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
    `--blog-font: var(--font-noto-sans-sc), var(--font-source-sans), sans-serif`,
  ].join("; ");

  return (
    <html
      lang="zh-CN"
      className={`${notoSansSc.variable} ${sourceSans.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <style>{`:root { ${cssVars} }`}</style>
        {children}
      </body>
    </html>
  );
}
