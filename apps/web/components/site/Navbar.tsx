"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Menu, X } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import NotificationBell from "@/components/site/NotificationBell";
import { authService } from "@/lib/services/authService";
import { getStoredAuth, purgeExpiredAuth } from "@/lib/auth/tokens";
import { siteConfig } from "@/lib/site.config";
import { cn } from "@/lib/utils";
import type { AuthUser } from "@/lib/types/auth";

const SCROLL_THRESHOLD = 100;

const drawerExtraLinks = [
  { href: "/settings", label: "Settings" },
  { href: "/about", label: "About" },
] as const;

export default function Navbar() {
  const [open, setOpen] = useState(false);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isScrolled, setIsScrolled] = useState(false);
  const pathname = usePathname();
  const router = useRouter();
  const brandMain = siteConfig.name.replace(/ Hub$/, "");
  const brandAccent = siteConfig.name.endsWith(" Hub") ? "Hub" : "";
  const drawerLinks = [...siteConfig.nav, ...drawerExtraLinks];

  const syncUser = useCallback(() => {
    purgeExpiredAuth();
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
    setOpen(false);
    router.push("/");
    router.refresh();
  };

  const isActive = (href: string) => pathname === href || pathname.startsWith(`${href}/`);

  const navLink = (href: string, label: string, className?: string) => (
    <Link
      href={href}
      className={cn("sakuga-nav-link flex items-center", isActive(href) && "active", className)}
      onClick={() => setOpen(false)}
    >
      {label}
    </Link>
  );

  const authLinks = user ? (
    <>
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
          Logout
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
        <button
          type="button"
          className="md:hidden p-2 -ml-2 text-text transition-colors duration-150"
          onClick={() => setOpen(true)}
          aria-label="Open navigation menu"
          aria-expanded={open}
        >
          <Menu size={22} />
        </button>

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
          {siteConfig.nav.map((item) => (
            <li key={item.href} className="flex items-stretch">
              {navLink(item.href, item.label)}
            </li>
          ))}
          {authLinks}
        </ul>

        <span className="md:hidden w-[38px]" aria-hidden="true" />
      </div>

      {open && (
        <div className="md:hidden fixed inset-0 z-[1200]">
          <button
            type="button"
            className="mobile-nav-backdrop"
            aria-label="Close navigation menu"
            onClick={() => setOpen(false)}
          />
          <aside className="mobile-nav-drawer" aria-label="Mobile navigation">
            <div className="mobile-nav-drawer__header">
              <span className="font-semibold text-text">Chtholly Hub</span>
              <button
                type="button"
                className="mobile-nav-drawer__close"
                onClick={() => setOpen(false)}
                aria-label="Close navigation menu"
              >
                <X size={20} />
              </button>
            </div>

            <div className="mobile-nav-drawer__body">
              {drawerLinks.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className={cn("mobile-nav-link", isActive(item.href) && "mobile-nav-link--active")}
                  onClick={() => setOpen(false)}
                >
                  {item.label}
                </Link>
              ))}
            </div>

            <div className="mobile-nav-drawer__footer">
              {user ? (
                <button
                  type="button"
                  className="mobile-nav-link mobile-nav-link--account"
                  onClick={() => void handleLogout()}
                >
                  Logout {user.nickname || user.phone}
                </button>
              ) : (
                <Link
                  href="/login"
                  className="mobile-nav-link mobile-nav-link--account"
                  onClick={() => setOpen(false)}
                >
                  Login
                </Link>
              )}
            </div>
          </aside>
        </div>
      )}
    </nav>
  );
}
