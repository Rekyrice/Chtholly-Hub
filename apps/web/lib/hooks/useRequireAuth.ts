"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { useStoredAuth } from "@/lib/auth/auth-store";

export function useRequireAuth(loginPath = "/login") {
  const router = useRouter();
  const pathname = usePathname();
  const authorized = Boolean(useStoredAuth()?.accessToken);

  useEffect(() => {
    if (authorized) return;
    const next = pathname ? `?next=${encodeURIComponent(pathname)}` : "";
    router.replace(`${loginPath}${next}`);
  }, [authorized, loginPath, pathname, router]);

  return authorized;
}
