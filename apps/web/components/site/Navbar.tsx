"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { ChevronDown, Menu } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import NotificationBell from "@/components/site/NotificationBell";
import { emitAuthChange, loadCurrentUserOnce, useStoredAuth } from "@/lib/auth/auth-store";
import { authService } from "@/lib/services/authService";
import { siteConfig } from "@/lib/site.config";
import { cn } from "@/lib/utils";

const SCROLL_THRESHOLD = 100;

const drawerExtraLinks = [
  { href: "/settings", label: "设置" },
  { href: "/about", label: "关于" },
] as const;

export default function Navbar() {
  const [menuOpen, setMenuOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const auth = useStoredAuth();
  const user = auth?.user ?? null;
  const [isScrolled, setIsScrolled] = useState(false);
  const mobileMenuRef = useRef<HTMLDivElement>(null);
  const userMenuRef = useRef<HTMLLIElement>(null);
  const pathname = usePathname();
  const router = useRouter();
  const brandMain = siteConfig.name.replace(/ Hub$/, "");
  const brandAccent = siteConfig.name.endsWith(" Hub") ? "Hub" : "";
  const drawerLinks = [...siteConfig.nav, ...drawerExtraLinks];
  const isAdmin = user?.role?.toLowerCase() === "admin";
  const roleLookupToken = auth?.accessToken && !user?.role ? auth.accessToken : null;

  useEffect(() => {
    if (!roleLookupToken) return;

    void loadCurrentUserOnce(roleLookupToken).then(emitAuthChange).catch(() => undefined);
  }, [roleLookupToken]);

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
      <li className="navbar-user-menu" ref={userMenuRef}>
        <button
          type="button"
          onClick={() => setUserMenuOpen((open) => !open)}
          className="navbar-user-menu__trigger"
          aria-haspopup="menu"
          aria-expanded={userMenuOpen}
        >
          {user.avatar ? (
            <img
              src={user.avatar}
              alt={user.nickname || user.phone || "User avatar"}
              className="navbar-user-menu__avatar"
            />
          ) : (
            <span className="navbar-user-menu__avatar navbar-user-menu__avatar--fallback">
              {(user.nickname || user.phone || "U").charAt(0).toUpperCase()}
            </span>
          )}
          <span className="navbar-user-menu__name">{user.nickname || user.phone}</span>
          <ChevronDown
            size={15}
            className={cn(
              "navbar-user-menu__chevron",
              userMenuOpen && "navbar-user-menu__chevron--open",
            )}
            aria-hidden="true"
          />
        </button>

        {userMenuOpen && (
          <div
            className="navbar-user-menu__dropdown"
            role="menu"
          >
            <Link
              href={`/user/${user.handle || user.id}`}
              className="navbar-user-menu__item"
              role="menuitem"
              onClick={() => setUserMenuOpen(false)}
            >
              我的主页
            </Link>
            <Link
              href="/profile/edit"
              className="navbar-user-menu__item"
              role="menuitem"
              onClick={() => setUserMenuOpen(false)}
            >
              编辑资料
            </Link>
            <Link
              href="/settings"
              className="navbar-user-menu__item"
              role="menuitem"
              onClick={() => setUserMenuOpen(false)}
            >
              设置
            </Link>
            <div className="navbar-user-menu__divider" />
            <button
              type="button"
              className="navbar-user-menu__item navbar-user-menu__item--danger"
              role="menuitem"
              onClick={() => void handleLogout()}
            >
              退出登录
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
