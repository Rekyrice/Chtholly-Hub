"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Menu, Search, X } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import NotificationBell from "@/components/site/NotificationBell";
import { authService } from "@/lib/services/authService";
import { getAccessToken, getStoredAuth } from "@/lib/auth/tokens";
import { siteConfig } from "@/lib/site.config";
import { cn } from "@/lib/utils";
import type { AuthUser } from "@/lib/types/auth";

const SCROLL_THRESHOLD = 100;

export default function Navbar() {
  const [open, setOpen] = useState(false);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isScrolled, setIsScrolled] = useState(false);
  const pathname = usePathname();
  const router = useRouter();
  const brandMain = siteConfig.name.replace(/ Hub$/, "");
  const brandAccent = siteConfig.name.endsWith(" Hub") ? "Hub" : "";

  const syncUser = useCallback(() => {
    getAccessToken();
    setUser(getStoredAuth()?.user ?? null);
  }, []);

  useEffect(() => {
    syncUser();
    window.addEventListener("chtholly-auth-change", syncUser);
    return () => window.removeEventListener("chtholly-auth-change", syncUser);
  }, [syncUser]);

  useEffect(() => {
    const onScroll = () => {
      setIsScrolled(window.scrollY > SCROLL_THRESHOLD);
    };

    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  const handleLogout = async () => {
    await authService.logout();
    setUser(null);
    window.dispatchEvent(new Event("chtholly-auth-change"));
    router.push("/");
    router.refresh();
  };

  const navLink = (href: string, label: string) => {
    const active = href === "/" ? pathname === "/" : pathname.startsWith(href);
    return (
      <Link
        href={href}
        className={cn("sakuga-nav-link flex items-center", active && "active")}
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
      <li className="flex items-center px-3 text-sm text-text-secondary">
        {user.nickname || user.phone}
      </li>
      <li className="flex items-center">
        <button
          type="button"
          onClick={handleLogout}
          className="sakuga-nav-link bg-transparent border-0 cursor-pointer transition-colors duration-150"
        >
          退出
        </button>
      </li>
    </>
  ) : (
    <li className="flex items-stretch">{navLink("/login", "Login")}</li>
  );

  return (
    <nav
      className={cn("sakuga-navbar", isScrolled && "sakuga-navbar--scrolled")}
      data-testid="navbar"
      data-scrolled={isScrolled}
    >
      <div className="sakuga-container w-full flex items-center justify-between">
        <Link href="/" className="flex items-center gap-2.5 py-2 shrink-0">
          <span className="navbar-brand-icon" aria-hidden="true">
            C
          </span>
          <span className="font-extrabold text-lg tracking-wide leading-none font-[Lato,'Noto_Sans_SC',sans-serif]">
            <span className="text-text">{brandMain}</span>
            {brandAccent && <span className="text-sky ml-1">{brandAccent}</span>}
          </span>
        </Link>

        <ul
          className={cn(
            "hidden md:flex items-stretch gap-1 transition-all duration-300 ease-in-out",
            isScrolled ? "h-[44px]" : "h-[52px]",
          )}
        >
          <li className="flex items-center px-2">
            <form action="/search" method="get" className="flex items-center">
              <input
                type="search"
                name="q"
                placeholder="搜索"
                aria-label="搜索帖子"
                data-testid="navbar-search"
                className="w-36 px-2 py-1 text-sm border border-border text-text bg-surface outline-none transition-colors duration-150 focus:border-sky rounded-md"
              />
              <button
                type="submit"
                className="ml-1 p-1.5 rounded-md text-text-secondary hover:bg-cloud transition-colors duration-150"
                aria-label="提交搜索"
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
          className="md:hidden p-2 text-text transition-colors duration-150"
          onClick={() => setOpen(!open)}
          aria-label="菜单"
        >
          {open ? <X size={22} /> : <Menu size={22} />}
        </button>
      </div>

      {open && (
        <div className="md:hidden absolute top-full left-0 right-0 z-50 mobile-nav-panel">
          {siteConfig.nav.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="mobile-nav-link"
              onClick={() => setOpen(false)}
            >
              {item.label}
            </Link>
          ))}
          <form
            action="/search"
            method="get"
            className="flex items-center gap-2 px-4 py-3 border-b border-border"
            onSubmit={() => setOpen(false)}
          >
            <input
              type="search"
              name="q"
              placeholder="搜索帖子"
              className="field-input flex-1 text-sm py-1.5"
            />
            <button type="submit" className="text-sky transition-colors duration-150">
              <Search size={18} />
            </button>
          </form>
          {user ? (
            <>
              <Link href="/write" className="mobile-nav-link" onClick={() => setOpen(false)}>
                Write
              </Link>
              <button
                type="button"
                className="mobile-nav-link w-full text-left text-sky border-b-0"
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
              className="mobile-nav-link border-b-0 text-sky"
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
