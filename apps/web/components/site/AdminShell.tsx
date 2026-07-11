"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { LayoutDashboard, MailWarning, ScrollText, Shield, Users } from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { emitAuthChange, useAuthUser } from "@/lib/auth/auth-store";
import { authService } from "@/lib/services/authService";
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
  const user = useAuthUser();
  const isAdmin = isAdminRole(user?.role);
  const [remoteAuthorized, setRemoteAuthorized] = useState<boolean | null>(null);
  const authorized = isAdmin ? true : remoteAuthorized;

  useEffect(() => {
    if (isAdmin) return;

    let alive = true;
    void authService.me()
      .then((user) => {
        if (!alive) return;
        emitAuthChange();
        if (isAdminRole(user.role)) {
          return;
        } else {
          setRemoteAuthorized(false);
          router.replace("/hub");
        }
      })
      .catch(() => {
        if (!alive) return;
        setRemoteAuthorized(false);
        router.replace("/hub");
      });

    return () => {
      alive = false;
    };
  }, [isAdmin, router]);

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
