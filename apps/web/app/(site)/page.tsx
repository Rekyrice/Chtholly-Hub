import type { Metadata } from "next";
import Link from "next/link";
import LandingTypewriter from "@/components/site/LandingTypewriter";
import { siteUrl } from "@/lib/site-url";

const landingDescription = "一个有灵魂的内容空间——灵魂是珂朵莉";
const landingImage = "/images/landing/default.jpg";

export const metadata: Metadata = {
  metadataBase: siteUrl,
  title: { absolute: "Chtholly Hub" },
  description: landingDescription,
  openGraph: {
    title: "Chtholly Hub",
    description: landingDescription,
    images: [landingImage],
  },
  twitter: {
    card: "summary_large_image",
    title: "Chtholly Hub",
    description: landingDescription,
    images: [landingImage],
  },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@type": "WebSite",
  name: "Chtholly Hub",
  description: landingDescription,
  url: siteUrl.href,
};

export default function LandingPage() {
  return (
    <div className="landing-page">
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <div className="landing-background" aria-hidden="true">
        <div className="landing-background__image" />
        <div className="landing-background__scrim" />
      </div>
      <div className="landing-content">
        <h1 className="landing-title">Chtholly Hub</h1>
        <LandingTypewriter />
        <Link href="/hub" className="landing-enter-btn">
          进入仓库
        </Link>
      </div>
    </div>
  );
}
