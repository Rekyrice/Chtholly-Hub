"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { getAccessToken } from "@/lib/auth/tokens";

export function useRequireAuth(loginPath = "/login") {
  const router = useRouter();
  const pathname = usePathname();
  const [authorized, setAuthorized] = useState(false);

  useEffect(() => {
    if (getAccessToken()) {
      /* eslint-disable-next-line react-hooks/set-state-in-effect -- auth is hydrated from browser storage */
      setAuthorized(true);
      return;
    }
    setAuthorized(false);
    const next = pathname ? `?next=${encodeURIComponent(pathname)}` : "";
    router.replace(`${loginPath}${next}`);
  }, [loginPath, pathname, router]);

  return authorized;
}
