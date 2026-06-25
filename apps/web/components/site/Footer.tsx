import { siteConfig } from "@/lib/site.config";

export default function Footer() {
  const year = new Date().getFullYear();

  return (
    <footer
      data-site-footer="true"
      style={{
        background: "rgba(34, 34, 34, var(--blog-footer-opacity))",
        color: "#ffffff",
        textAlign: "center",
        padding: "16px",
        minHeight: "var(--blog-footer-height)",
        fontSize: 16,
        letterSpacing: 1,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        gap: 4,
        marginTop: "auto",
      }}
    >
      <p style={{ margin: 0, color: "#ffffff" }}>
        © {year} {siteConfig.author.name} · {siteConfig.name}
      </p>
      <p style={{ margin: 0, fontSize: 13, color: "rgba(255,255,255,0.4)" }}>
        Powered by Next.js &amp; Spring Boot
      </p>
    </footer>
  );
}
