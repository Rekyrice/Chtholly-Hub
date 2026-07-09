"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { ChevronRight, House, UserRound } from "lucide-react";
import { getAccessToken, getStoredAuth } from "@/lib/auth/tokens";
import type { AuthUser } from "@/lib/types/auth";

export default function SettingsPage() {
  const router = useRouter();
  const [user, setUser] = useState<AuthUser | null>(null);

  useEffect(() => {
    if (!getAccessToken()) {
      router.replace("/login");
      return;
    }
    setUser(getStoredAuth()?.user ?? null);
  }, [router]);

  return (
    <main className="settings-page" data-testid="settings-page">
      <section className="settings-shell settings-shell--narrow">
        <div className="settings-header">
          <p className="settings-eyebrow">Account</p>
          <h1>设置</h1>
          <p>管理账号与偏好。资料编辑请从「编辑资料」进入。</p>
        </div>

        <div className="settings-menu">
          <Link href="/profile/edit" className="settings-menu__item">
            <span className="settings-menu__icon" aria-hidden="true">
              <UserRound size={18} />
            </span>
            <span className="settings-menu__copy">
              <strong>编辑资料</strong>
              <small>头像、昵称、签名与兴趣标签</small>
            </span>
            <ChevronRight size={18} className="settings-menu__chevron" aria-hidden="true" />
          </Link>

          {user && (
            <Link
              href={`/user/${user.handle || user.id}`}
              className="settings-menu__item"
            >
              <span className="settings-menu__icon" aria-hidden="true">
                <House size={18} />
              </span>
              <span className="settings-menu__copy">
                <strong>我的主页</strong>
                <small>查看对外展示的个人主页</small>
              </span>
              <ChevronRight size={18} className="settings-menu__chevron" aria-hidden="true" />
            </Link>
          )}
        </div>

        <p className="settings-footnote">更多通用偏好（通知、隐私等）会陆续补上。</p>
      </section>
    </main>
  );
}
