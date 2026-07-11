"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Heart, Home, Pen, Search, User } from "lucide-react";
import { useAuthUser } from "@/lib/auth/auth-store";
import { cn } from "@/lib/utils";
import type { AuthUser } from "@/lib/types/auth";

const navItems = [
  { href: "/hub", label: "Hub", icon: Home },
  { href: "/chtholly", label: "Chtholly", icon: Heart },
  { href: "/search", label: "Search", icon: Search },
  { href: "/write", label: "Write", icon: Pen },
] as const;

function NavUserAvatar({ user, size = 24 }: { user: AuthUser | null; size?: number }) {
  if (user?.avatar) {
    return (
      <img
        src={user.avatar}
        alt=""
        width={size}
        height={size}
        className="mobile-bottom-nav__avatar"
        aria-hidden="true"
      />
    );
  }

  if (user) {
    const initial = (user.nickname?.[0] ?? user.phone?.slice(-1) ?? "?").toUpperCase();
    return (
      <span
        className="mobile-bottom-nav__avatar mobile-bottom-nav__avatar--initial"
        style={{ width: size, height: size, fontSize: size * 0.45 }}
        aria-hidden="true"
      >
        {initial}
      </span>
    );
  }

  return <User size={size} strokeWidth={2} aria-hidden="true" />;
}

export default function MobileBottomNav() {
  const pathname = usePathname();
  const user = useAuthUser();

  const profileHref = user ? `/user/${user.handle || user.id}` : "/login";
  const profileActive =
    pathname.startsWith("/user/") ||
    pathname === "/settings" ||
    pathname.startsWith("/settings/") ||
    pathname.startsWith("/profile/");

  return (
    <nav className="mobile-bottom-nav md:hidden" aria-label="Mobile navigation">
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
        aria-label={user ? "我的主页" : "登录"}
        aria-current={profileActive ? "page" : undefined}
      >
        <NavUserAvatar user={user} size={24} />
      </Link>
    </nav>
  );
}
