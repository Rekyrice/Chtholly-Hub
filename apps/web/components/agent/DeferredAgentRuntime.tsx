"use client";

import dynamic, { type DynamicOptionsLoadingProps } from "next/dynamic";
import { usePathname } from "next/navigation";
import {
  Component,
  createContext,
  useContext,
  useEffect,
  useRef,
  type ErrorInfo,
  type ReactNode,
} from "react";
import { getAgentRuntimePolicy } from "@/components/agent/agentRuntimePolicy";
import { useStoredAuth } from "@/lib/auth/auth-store";

const AgentRuntimeResetKeyContext = createContext("");

export function RuntimeLoading({ error, retry }: DynamicOptionsLoadingProps) {
  const resetKey = useContext(AgentRuntimeResetKeyContext);
  const loggedErrorRef = useRef<Error | null>(null);
  const failedResetKeyRef = useRef<string | null>(null);
  const retriedResetKeyRef = useRef<string | null>(null);

  useEffect(() => {
    if (!error) {
      loggedErrorRef.current = null;
      failedResetKeyRef.current = null;
      retriedResetKeyRef.current = null;
      return;
    }

    if (loggedErrorRef.current !== error) {
      loggedErrorRef.current = error;
      failedResetKeyRef.current = resetKey;
      retriedResetKeyRef.current = null;
      console.error("Deferred Agent runtime chunk failed", error);
      return;
    }

    if (
      failedResetKeyRef.current !== null
      && resetKey !== failedResetKeyRef.current
      && retriedResetKeyRef.current !== resetKey
      && retry
    ) {
      retriedResetKeyRef.current = resetKey;
      retry();
    }
  }, [error, resetKey, retry]);

  return null;
}

const AuthenticatedAgentRuntime = dynamic(
  () => import("@/components/agent/AuthenticatedAgentRuntime"),
  {
    ssr: false,
    loading: (props) => <RuntimeLoading {...props} />,
  },
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

  if (!storedAuth || policy.agentWorkspace || policy.chthollyRoom) return null;

  const resetKey = `${pathname}:${storedAuth.accessToken}`;
  return (
    <AgentRuntimeResetKeyContext.Provider value={resetKey}>
      <AgentRuntimeErrorBoundary resetKey={resetKey}>
        <AuthenticatedAgentRuntime />
      </AgentRuntimeErrorBoundary>
    </AgentRuntimeResetKeyContext.Provider>
  );
}
