import { siteConfig } from "@/lib/site.config";

export default function SiteHeader() {
  return (
    <header className="site-header">
      <div className="site-header-overlay" />
      <div className="site-header-content">
        <h1 className="site-header-title">{siteConfig.name}</h1>
        <p className="site-header-desc">{siteConfig.description}</p>
      </div>
    </header>
  );
}
