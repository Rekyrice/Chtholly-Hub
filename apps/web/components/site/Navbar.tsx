"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Menu, X } from "lucide-react";
import { useState } from "react";
import { siteConfig } from "@/lib/site.config";

export default function Navbar() {
  const [open, setOpen] = useState(false);
  const pathname = usePathname();
  const brandMain = siteConfig.name.replace(/ Hub$/, "");
  const brandAccent = siteConfig.name.endsWith(" Hub") ? "Hub" : "";

  return (
    <nav className="sakuga-navbar">
      <div className="sakuga-container w-full flex items-center justify-between">
        <Link href="/" className="flex items-center gap-2 py-2.5 flex-shrink-0">
          <span
            className="inline-flex items-center justify-center rounded-full text-white text-sm font-bold"
            style={{ width: 32, height: 32, backgroundColor: siteConfig.theme.primary }}
          >
            仁
          </span>
          <span
            style={{
              fontFamily: 'Lato, "Noto Sans SC", sans-serif',
              fontWeight: 800,
              fontSize: 18,
              letterSpacing: 0.6,
              lineHeight: 1,
            }}
          >
            <span style={{ color: "#101114" }}>{brandMain}</span>
            {brandAccent && (
              <span style={{ color: siteConfig.theme.primary, marginLeft: 4 }}>
                {brandAccent}
              </span>
            )}
          </span>
        </Link>

        <ul className="hidden md:flex items-stretch h-[52px]">
          {siteConfig.nav.map((item) => {
            const active =
              item.href === "/"
                ? pathname === "/"
                : pathname.startsWith(item.href);
            return (
              <li key={item.href} className="flex items-stretch">
                <Link
                  href={item.href}
                  className={`sakuga-nav-link flex items-center ${active ? "active" : ""}`}
                >
                  {item.label}
                </Link>
              </li>
            );
          })}
        </ul>

        <button
          type="button"
          className="md:hidden p-2"
          style={{ color: "#333" }}
          onClick={() => setOpen(!open)}
          aria-label="菜单"
        >
          {open ? <X size={22} /> : <Menu size={22} />}
        </button>
      </div>

      {open && (
        <div
          className="md:hidden absolute top-full left-0 right-0 z-50"
          style={{ background: "#fff", borderTop: "1px solid #eee" }}
        >
          {siteConfig.nav.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="block px-4 py-3 text-sm uppercase tracking-wide border-b"
              style={{ color: "#333", borderColor: "#f5f5f5" }}
              onClick={() => setOpen(false)}
            >
              {item.label}
            </Link>
          ))}
        </div>
      )}
    </nav>
  );
}
