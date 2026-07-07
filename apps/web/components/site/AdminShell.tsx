"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { LayoutDashboard, MailWarning, ScrollText, Shield, Users } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { authService } from "@/lib/services/authService";
import { getStoredAuth } from "@/lib/auth/tokens";
import { cn } from "@/lib/utils";

type AdminShellProps = {
  children: ReactNode;
};

const ADMIN_LINKS = [
  { href: "/admin", label: "概览", icon: LayoutDashboard },
  { href: "/admin/users", label: "用户管理", icon: Users },
  { href: "/admin/posts", label: "内容管理", icon: ScrollText },
  { href: "/admin/dead-letter", label: "死信队列", icon: MailWarning },
  { href: "/admin/traces", label: "Trace Dashboard", icon: Shield },
] as const;

function isAdminRole(role?: string | null) {
  return role?.toLowerCase() === "admin";
}

export default function AdminShell({ children }: AdminShellProps) {
  const pathname = usePathname();
  const router = useRouter();
  const [authorized, setAuthorized] = useState<boolean | null>(null);

  useEffect(() => {
    let alive = true;
    const stored = getStoredAuth()?.user;
    if (isAdminRole(stored?.role)) {
      setAuthorized(true);
      return;
    }

    void authService.me()
      .then((user) => {
        if (!alive) return;
        if (isAdminRole(user.role)) {
          setAuthorized(true);
        } else {
          setAuthorized(false);
          router.replace("/hub");
        }
      })
      .catch(() => {
        if (!alive) return;
        setAuthorized(false);
        router.replace("/hub");
      });

    return () => {
      alive = false;
    };
  }, [router]);

  const activePath = useMemo(() => {
    if (pathname === "/admin") return "/admin";
    return ADMIN_LINKS.find((item) => pathname.startsWith(`${item.href}/`) || pathname === item.href)?.href ?? "/admin";
  }, [pathname]);

  if (authorized !== true) {
    return (
      <div className="admin-shell admin-shell--loading">
        <div className="admin-loading-card">
          <p>正在确认管理员身份……</p>
        </div>
      </div>
    );
  }

  return (
    <div className="admin-shell">
      <aside className="admin-sidebar" aria-label="Admin navigation">
        <div className="admin-sidebar__brand">
          <span>CH</span>
          <div>
            <strong>Admin</strong>
            <small>Chtholly Hub</small>
          </div>
        </div>
        <nav className="admin-sidebar__nav">
          {ADMIN_LINKS.map((item) => {
            const Icon = item.icon;
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn("admin-sidebar__link", activePath === item.href && "admin-sidebar__link--active")}
              >
                <Icon size={17} />
                <span>{item.label}</span>
              </Link>
            );
          })}
        </nav>
      </aside>
      <main className="admin-main">{children}</main>
    </div>
  );
}
