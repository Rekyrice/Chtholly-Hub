"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Heart, Home, Pen, Search, User } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { getStoredAuth, purgeExpiredAuth } from "@/lib/auth/tokens";
import { cn } from "@/lib/utils";
import type { AuthUser } from "@/lib/types/auth";

const navItems = [
  { href: "/hub", label: "Hub", icon: Home },
  { href: "/chtholly", label: "Chtholly", icon: Heart },
  { href: "/search", label: "Search", icon: Search },
  { href: "/write", label: "Write", icon: Pen },
] as const;

export default function MobileBottomNav() {
  const pathname = usePathname();
  const [user, setUser] = useState<AuthUser | null>(null);

  const syncUser = useCallback(() => {
    purgeExpiredAuth();
    setUser(getStoredAuth()?.user ?? null);
  }, []);

  useEffect(() => {
    syncUser();
    window.addEventListener("chtholly-auth-change", syncUser);
    return () => window.removeEventListener("chtholly-auth-change", syncUser);
  }, [syncUser]);

  const profileHref = user?.handle ? `/user/${user.handle}` : "/login";
  const profileActive = pathname.startsWith("/user") || pathname.startsWith("/login");

  return (
    <nav className="mobile-bottom-nav md:hidden" aria-label="Mobile bottom navigation">
      {navItems.map((item) => {
        const Icon = item.icon;
        const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
        return (
          <Link
            key={item.href}
            href={item.href}
            className={cn("mobile-bottom-nav__item", active && "mobile-bottom-nav__item--active")}
            aria-current={active ? "page" : undefined}
          >
            <Icon size={20} strokeWidth={2} aria-hidden="true" />
            <span>{item.label}</span>
          </Link>
        );
      })}
      <Link
        href={profileHref}
        className={cn("mobile-bottom-nav__item", profileActive && "mobile-bottom-nav__item--active")}
        aria-current={profileActive ? "page" : undefined}
      >
        <User size={20} strokeWidth={2} aria-hidden="true" />
        <span>Me</span>
      </Link>
    </nav>
  );
}
