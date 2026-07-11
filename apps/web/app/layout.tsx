import type { Metadata } from "next";
import {
  JetBrains_Mono,
  Noto_Sans_JP,
  Noto_Sans_SC,
  Playfair_Display,
  Source_Sans_3,
} from "next/font/google";
import { siteConfig } from "@/lib/site.config";
import { siteUrl } from "@/lib/site-url";
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

const playfair = Playfair_Display({
  subsets: ["latin"],
  variable: "--font-serif",
  display: "swap",
});

const notoSansJp = Noto_Sans_JP({
  subsets: ["latin"],
  weight: ["400", "500", "700"],
  variable: "--font-jp",
  display: "swap",
});

const jetbrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap",
});

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
    `--blog-font: var(--font-noto-sans-sc), var(--font-source-sans), sans-serif`,
  ].join("; ");

  return (
    <html
      lang="zh-CN"
      className={`${notoSansSc.variable} ${sourceSans.variable} ${playfair.variable} ${notoSansJp.variable} ${jetbrainsMono.variable} h-full antialiased`}
      suppressHydrationWarning
    >
      <body className="min-h-full flex flex-col" suppressHydrationWarning>
        <style>{`:root { ${cssVars} }`}</style>
        {children}
      </body>
    </html>
  );
}
