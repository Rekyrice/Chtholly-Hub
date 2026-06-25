import { siteConfig } from "@/lib/site.config";

export default function SiteHeader() {
  return (
    <header
      style={{
        position: "relative",
        width: "100%",
        height: 480,
        backgroundImage:
          "linear-gradient(135deg, #00695c 0%, #009688 50%, #607d8b 100%)",
        backgroundSize: "cover",
        backgroundPosition: "50% 0%",
        overflow: "hidden",
        WebkitMaskImage:
          "linear-gradient(to bottom, #000 0%, #000 76%, rgba(0,0,0,0.82) 88%, rgba(0,0,0,0.36) 95%, rgba(0,0,0,0) 100%)",
        maskImage:
          "linear-gradient(to bottom, #000 0%, #000 76%, rgba(0,0,0,0.82) 88%, rgba(0,0,0,0.36) 95%, rgba(0,0,0,0) 100%)",
      }}
    >
      <div
        style={{
          position: "absolute",
          inset: 0,
          background: `rgba(0,0,0,var(--blog-header-overlay))`,
        }}
      />
      <div
        style={{
          position: "absolute",
          top: "50%",
          left: "50%",
          transform: "translate(-50%, -50%)",
          textAlign: "center",
          width: "100%",
          padding: "0 20px",
        }}
      >
        <h1
          style={{
            fontFamily: '"Noto Sans SC", Lato, sans-serif',
            fontSize: 63,
            fontWeight: 700,
            color: "var(--blog-site-name-color)",
            textTransform: "uppercase",
            letterSpacing: 4,
            lineHeight: 1.2,
            margin: 0,
            textShadow: "0 2px 8px rgba(0,0,0,0.5)",
          }}
        >
          {siteConfig.name}
        </h1>
        <p
          style={{
            fontFamily: '"Noto Sans SC", Lato, sans-serif',
            fontSize: 18,
            fontWeight: 400,
            color: "var(--blog-site-desc-color)",
            marginTop: 12,
            opacity: 0.9,
            letterSpacing: 1,
            textShadow: "0 1px 4px rgba(0,0,0,0.4)",
          }}
        >
          {siteConfig.description}
        </p>
      </div>
    </header>
  );
}
