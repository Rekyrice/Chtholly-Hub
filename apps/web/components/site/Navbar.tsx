"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { ChevronDown, Menu } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
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
  const [menuOpen, setMenuOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isScrolled, setIsScrolled] = useState(false);
  const mobileMenuRef = useRef<HTMLDivElement>(null);
  const userMenuRef = useRef<HTMLLIElement>(null);
  const pathname = usePathname();
  const router = useRouter();
  const brandMain = siteConfig.name.replace(/ Hub$/, "");
  const brandAccent = siteConfig.name.endsWith(" Hub") ? "Hub" : "";
  const drawerLinks = [...siteConfig.nav, ...drawerExtraLinks];
  const isAdmin = user?.role?.toLowerCase() === "admin";

  const syncUser = useCallback(() => {
    purgeExpiredAuth();
    const stored = getStoredAuth();
    setUser(stored?.user ?? null);
    if (stored?.accessToken && !stored.user?.role) {
      void authService.me().then(setUser).catch(() => undefined);
    }
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

  useEffect(() => {
    if (!menuOpen) return;

    const onPointerDown = (event: MouseEvent) => {
      if (!mobileMenuRef.current?.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };

    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [menuOpen]);

  useEffect(() => {
    if (!userMenuOpen) return;

    const onPointerDown = (event: MouseEvent) => {
      if (!userMenuRef.current?.contains(event.target as Node)) {
        setUserMenuOpen(false);
      }
    };

    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [userMenuOpen]);

  const handleLogout = async () => {
    await authService.logout();
    setUser(null);
    window.dispatchEvent(new Event("chtholly-auth-change"));
    setMenuOpen(false);
    setUserMenuOpen(false);
    router.push("/");
    router.refresh();
  };

  const isActive = (href: string) => pathname === href || pathname.startsWith(`${href}/`);

  const navLink = (href: string, label: string, className?: string) => (
    <Link
      href={href}
      className={cn("sakuga-nav-link flex items-center", isActive(href) && "active", className)}
      onClick={() => setMenuOpen(false)}
    >
      {label}
    </Link>
  );

  const authLinks = user ? (
    <>
      <li className="flex items-center">
        <NotificationBell />
      </li>
      <li className="relative flex items-center" ref={userMenuRef}>
        <button
          type="button"
          onClick={() => setUserMenuOpen((open) => !open)}
          className="sakuga-nav-link flex items-center gap-2 bg-transparent border-0 cursor-pointer transition-colors duration-150"
          aria-haspopup="menu"
          aria-expanded={userMenuOpen}
        >
          {user.avatar ? (
            <img
              src={user.avatar}
              alt={user.nickname || user.phone || "User avatar"}
              className="h-7 w-7 rounded-full object-cover"
            />
          ) : (
            <span className="flex h-7 w-7 items-center justify-center rounded-full bg-sky/15 text-xs font-semibold text-sky">
              {(user.nickname || user.phone || "U").charAt(0).toUpperCase()}
            </span>
          )}
          <span className="max-w-[8rem] truncate">{user.nickname || user.phone}</span>
          <ChevronDown
            size={15}
            className={cn(
              "transition-transform duration-150",
              userMenuOpen && "rotate-180",
            )}
            aria-hidden="true"
          />
        </button>

        {userMenuOpen && (
          <div
            className="animate-in fade-in slide-in-from-top-2 absolute right-0 top-full z-50 mt-1 w-48 overflow-hidden rounded-xl border border-black/10 bg-white/95 py-1 text-sm shadow-lg backdrop-blur-xl dark:border-white/10 dark:bg-slate-950/95"
            role="menu"
          >
            <Link
              href={`/user/${user.handle || user.id}`}
              className="flex items-center gap-2 px-4 py-2.5 text-text transition-colors hover:bg-black/5 dark:hover:bg-white/10"
              role="menuitem"
              onClick={() => setUserMenuOpen(false)}
            >
              Profile
            </Link>
            <Link
              href="/settings"
              className="flex items-center gap-2 px-4 py-2.5 text-text transition-colors hover:bg-black/5 dark:hover:bg-white/10"
              role="menuitem"
              onClick={() => setUserMenuOpen(false)}
            >
              Settings
            </Link>
            <div className="my-1 h-px bg-black/10 dark:bg-white/10" />
            <button
              type="button"
              className="flex w-full items-center gap-2 px-4 py-2.5 text-left text-red-500 transition-colors hover:bg-red-50 dark:hover:bg-red-950/30"
              role="menuitem"
              onClick={() => void handleLogout()}
            >
              Logout
            </button>
          </div>
        )}
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
      <div className="sakuga-container relative w-full flex items-center justify-between">
        <div className="header-mobile-menu" ref={mobileMenuRef}>
          <button
            type="button"
            className="header-mobile-menu__toggle"
            onClick={() => setMenuOpen((open) => !open)}
            aria-label="Toggle menu"
            aria-expanded={menuOpen}
          >
            <Menu size={24} />
          </button>
          {menuOpen && (
            <div className="header-mobile-dropdown">
              {drawerLinks.map((link) => (
                <Link
                  key={link.href}
                  href={link.href}
                  className={cn(isActive(link.href) && "header-mobile-dropdown__link--active")}
                  onClick={() => setMenuOpen(false)}
                >
                  {link.label}
                </Link>
              ))}
              {isAdmin && (
                <Link
                  href="/admin"
                  className={cn(isActive("/admin") && "header-mobile-dropdown__link--active")}
                  onClick={() => setMenuOpen(false)}
                >
                  Admin
                </Link>
              )}
              {user ? (
                <button
                  type="button"
                  className="header-mobile-dropdown__account"
                  onClick={() => void handleLogout()}
                >
                  Logout
                </button>
              ) : (
                <Link href="/login" onClick={() => setMenuOpen(false)}>
                  Login
                </Link>
              )}
            </div>
          )}
        </div>

        <Link href="/hub" className="flex items-center gap-2.5 py-2 shrink-0">
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
            "header-desktop-nav hidden md:flex items-stretch gap-1 transition-all duration-300 ease-in-out",
            isScrolled ? "h-[44px]" : "h-[52px]",
          )}
        >
          {siteConfig.nav.map((item) => (
            <li key={item.href} className="flex items-stretch">
              {navLink(item.href, item.label)}
            </li>
          ))}
          {isAdmin && (
            <li className="flex items-stretch">
              {navLink("/admin", "Admin")}
            </li>
          )}
          {authLinks}
        </ul>

        <span className="header-mobile-menu-spacer md:hidden" aria-hidden="true" />
      </div>
    </nav>
  );
}
