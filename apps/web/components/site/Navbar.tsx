"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Menu, Search, X } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { getAccessToken, getStoredAuth } from "@/lib/auth/tokens";
import { authService } from "@/lib/services/authService";
import NotificationBell from "@/components/site/NotificationBell";
import { siteConfig } from "@/lib/site.config";
import type { AuthUser } from "@/lib/types/auth";

export default function Navbar() {
  const [open, setOpen] = useState(false);
  // 首屏必须与 SSR 一致为 null，登录态仅在 mount 后从 localStorage 同步
  const [user, setUser] = useState<AuthUser | null>(null);
  const pathname = usePathname();
  const router = useRouter();
  const brandMain = siteConfig.name.replace(/ Hub$/, "");
  const brandAccent = siteConfig.name.endsWith(" Hub") ? "Hub" : "";

  const syncUser = useCallback(() => {
    getAccessToken(); // 顺带清理过期 token
    setUser(getStoredAuth()?.user ?? null);
  }, []);

  useEffect(() => {
    syncUser();
    window.addEventListener("chtholly-auth-change", syncUser);
    return () => window.removeEventListener("chtholly-auth-change", syncUser);
  }, [syncUser]);

  const handleLogout = async () => {
    await authService.logout();
    setUser(null);
    window.dispatchEvent(new Event("chtholly-auth-change"));
    router.push("/");
    router.refresh();
  };

  const navLink = (href: string, label: string) => {
    const active =
      href === "/" ? pathname === "/" : pathname.startsWith(href);
    return (
      <Link
        href={href}
        className={`sakuga-nav-link flex items-center ${active ? "active" : ""}`}
        onClick={() => setOpen(false)}
      >
        {label}
      </Link>
    );
  };

  const authLinks = user ? (
    <>
      <li className="flex items-stretch">{navLink("/write", "Write")}</li>
      <li className="flex items-center">
        <NotificationBell />
      </li>
      <li className="flex items-center px-3 text-sm" style={{ color: "#727272" }}>
        {user.nickname || user.phone}
      </li>
      <li className="flex items-center">
        <button
          type="button"
          onClick={handleLogout}
          className="sakuga-nav-link"
          style={{ background: "none", border: "none", cursor: "pointer" }}
        >
          退出
        </button>
      </li>
    </>
  ) : (
    <li className="flex items-stretch">{navLink("/login", "Login")}</li>
  );

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

        <ul className="hidden md:flex items-stretch h-[52px] gap-1">
          <li className="flex items-center px-2">
            <form action="/search" method="get" className="flex items-center">
              <input
                type="search"
                name="q"
                placeholder="搜索"
                aria-label="搜索帖子"
                className="w-36 px-2 py-1 text-sm border outline-none focus:border-[#009688]"
                style={{ borderColor: "#e0e0e0", color: "#424242" }}
              />
              <button
                type="submit"
                className="ml-1 p-1.5 rounded hover:bg-black/5"
                aria-label="提交搜索"
                style={{ color: "#727272" }}
              >
                <Search size={18} />
              </button>
            </form>
          </li>
          {siteConfig.nav.map((item) => (
            <li key={item.href} className="flex items-stretch">
              {navLink(item.href, item.label)}
            </li>
          ))}
          {authLinks}
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
          <form
            action="/search"
            method="get"
            className="flex items-center gap-2 px-4 py-3 border-b"
            style={{ borderColor: "#f5f5f5" }}
            onSubmit={() => setOpen(false)}
          >
            <input
              type="search"
              name="q"
              placeholder="搜索帖子"
              className="flex-1 px-2 py-1.5 text-sm border outline-none"
              style={{ borderColor: "#e0e0e0" }}
            />
            <button type="submit" style={{ color: siteConfig.theme.primary }}>
              <Search size={18} />
            </button>
          </form>
          {user ? (
            <>
              <Link
                href="/write"
                className="block px-4 py-3 text-sm uppercase tracking-wide border-b"
                style={{ color: "#333", borderColor: "#f5f5f5" }}
                onClick={() => setOpen(false)}
              >
                Write
              </Link>
              <button
                type="button"
                className="block w-full text-left px-4 py-3 text-sm uppercase tracking-wide"
                style={{ color: "#009688" }}
                onClick={() => {
                  setOpen(false);
                  void handleLogout();
                }}
              >
                退出 ({user.nickname || user.phone})
              </button>
            </>
          ) : (
            <Link
              href="/login"
              className="block px-4 py-3 text-sm uppercase tracking-wide"
              style={{ color: "#009688" }}
              onClick={() => setOpen(false)}
            >
              Login
            </Link>
          )}
        </div>
      )}
    </nav>
  );
}
