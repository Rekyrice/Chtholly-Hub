"use client";

import dynamic from "next/dynamic";
import { usePathname } from "next/navigation";
import { Component, type ErrorInfo, type ReactNode } from "react";
import { getAgentRuntimePolicy } from "@/components/agent/agentRuntimePolicy";
import { useStoredAuth } from "@/lib/auth/auth-store";

const AuthenticatedAgentRuntime = dynamic(
  () => import("@/components/agent/AuthenticatedAgentRuntime"),
  { ssr: false },
);

type AgentRuntimeErrorBoundaryProps = {
  children: ReactNode;
  resetKey: string;
};

type AgentRuntimeErrorBoundaryState = {
  failed: boolean;
  resetKey: string;
};

export class AgentRuntimeErrorBoundary extends Component<
  AgentRuntimeErrorBoundaryProps,
  AgentRuntimeErrorBoundaryState
> {
  state: AgentRuntimeErrorBoundaryState = {
    failed: false,
    resetKey: this.props.resetKey,
  };

  static getDerivedStateFromProps(
    props: AgentRuntimeErrorBoundaryProps,
    state: AgentRuntimeErrorBoundaryState,
  ): Partial<AgentRuntimeErrorBoundaryState> | null {
    if (props.resetKey !== state.resetKey) {
      return { failed: false, resetKey: props.resetKey };
    }
    return null;
  }

  static getDerivedStateFromError(): Partial<AgentRuntimeErrorBoundaryState> {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("Deferred Agent runtime failed", error, info);
  }

  render() {
    return this.state.failed ? null : this.props.children;
  }
}

export default function DeferredAgentRuntime() {
  const pathname = usePathname();
  const storedAuth = useStoredAuth();
  const policy = getAgentRuntimePolicy(pathname);

  if (!storedAuth || policy.agentWorkspace) return null;

  const resetKey = `${pathname}:${storedAuth.accessToken}`;
  return (
    <AgentRuntimeErrorBoundary resetKey={resetKey}>
      <AuthenticatedAgentRuntime />
    </AgentRuntimeErrorBoundary>
  );
}
