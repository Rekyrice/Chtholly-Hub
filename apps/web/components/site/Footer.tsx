import { siteConfig } from "@/lib/site.config";

export default function Footer() {
  const year = new Date().getFullYear();

  return (
    <footer data-site-footer="true" className="site-footer">
      <p className="m-0">
        © {year} {siteConfig.author.name} · {siteConfig.name}
      </p>
      <p className="site-footer-muted">
        Powered by Next.js &amp; Spring Boot
      </p>
    </footer>
  );
}
