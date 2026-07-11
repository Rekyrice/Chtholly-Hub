"use client";

import dynamic from "next/dynamic";
import { usePathname } from "next/navigation";
import { useStoredAuth } from "@/lib/auth/auth-store";

const AuthenticatedAgentRuntime = dynamic(
  () => import("@/components/agent/AuthenticatedAgentRuntime"),
  { ssr: false },
);

export function isAgentWorkspacePath(pathname: string): boolean {
  return pathname === "/agent" || pathname.startsWith("/agent/");
}

export default function DeferredAgentRuntime() {
  const pathname = usePathname();
  const storedAuth = useStoredAuth();

  if (!storedAuth || isAgentWorkspacePath(pathname)) return null;

  return <AuthenticatedAgentRuntime />;
}
