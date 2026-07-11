"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { useAgentPreferences } from "@/components/agent/hooks/useAgentPreferences";
import { useAgentSessions } from "@/components/agent/hooks/useAgentSessions";
import { useAgentWebSocket } from "@/components/agent/hooks/useAgentWebSocket";
import type { AgentSessionRecord } from "@/lib/agent/sessions";
import { isLoggedIn, purgeExpiredAuth } from "@/lib/auth/tokens";
import type { ChatMessage, ProactiveNotificationItem } from "@/lib/types/agent";
import type { AgentLivePhase } from "@/lib/types/live2d";

type BooleanStateAction = boolean | ((previous: boolean) => boolean);

type AgentChatContextValue = {
  loggedIn: boolean;
  activeSessionId: string;
  sessions: AgentSessionRecord[];
  messages: ChatMessage[];
  input: string;
  setInput: (value: string) => void;
  connected: boolean;
  busy: boolean;
  showSteps: boolean;
  setShowSteps: (value: BooleanStateAction) => void;
  workspaceDark: boolean;
  setWorkspaceDark: (value: BooleanStateAction) => void;
  richMarkdown: boolean;
  setRichMarkdown: (value: BooleanStateAction) => void;
  liveSteps: string[];
  livePhase: AgentLivePhase;
  streaming: boolean;
  lastError: string | null;
  proactiveNotifications: ProactiveNotificationItem[];
  visibleProactiveNotification: ProactiveNotificationItem | null;
  dismissProactiveNotification: () => void;
  sendMessage: (text: string) => Promise<void>;
  clearConversation: () => void;
  switchSession: (sessionId: string) => void;
  createSession: () => string;
  renameSession: (sessionId: string, title: string) => void;
  deleteSession: (sessionId: string) => void;
  fillAndSend: (text: string) => void;
};

const AgentChatContext = createContext<AgentChatContextValue | null>(null);

export function AgentChatProvider({ children }: { children: ReactNode }) {
  const [loggedIn, setLoggedIn] = useState(false);
  const [input, setInput] = useState("");
  const preferences = useAgentPreferences();
  const sessionState = useAgentSessions();

  const syncAuth = useCallback(() => {
    purgeExpiredAuth();
    setLoggedIn(isLoggedIn());
  }, []);

  useEffect(() => {
    /* eslint-disable-next-line react-hooks/set-state-in-effect -- auth is hydrated from browser storage */
    syncAuth();
    window.addEventListener("chtholly-auth-change", syncAuth);
    return () => window.removeEventListener("chtholly-auth-change", syncAuth);
  }, [syncAuth]);

  const consumeInput = useCallback(() => setInput(""), []);
  const socketState = useAgentWebSocket({
    loggedIn,
    hydrated: sessionState.hydrated,
    activeSessionIdRef: sessionState.activeSessionIdRef,
    messages: sessionState.messages,
    setMessages: sessionState.setMessages,
    onInputConsumed: consumeInput,
  });

  const resetConversationView = useCallback(() => {
    setInput("");
    socketState.resetTransient();
  }, [socketState]);

  const switchSession = useCallback(
    (sessionId: string) => {
      if (sessionState.switchSession(sessionId)) resetConversationView();
    },
    [resetConversationView, sessionState],
  );

  const createSession = useCallback(() => {
    const id = sessionState.createSession();
    resetConversationView();
    return id;
  }, [resetConversationView, sessionState]);

  const deleteSession = useCallback(
    (sessionId: string) => {
      socketState.clearBackendMemory(sessionId);
      if (sessionState.deleteSession(sessionId)) resetConversationView();
    },
    [resetConversationView, sessionState, socketState],
  );

  const fillAndSend = useCallback(
    (text: string) => {
      setInput(text);
      void socketState.sendMessage(text);
    },
    [socketState],
  );

  const value = useMemo<AgentChatContextValue>(
    () => ({
      loggedIn,
      activeSessionId: sessionState.activeSessionId,
      sessions: sessionState.sessions,
      messages: sessionState.messages,
      input,
      setInput,
      connected: socketState.connected,
      busy: socketState.busy,
      ...preferences,
      liveSteps: socketState.liveSteps,
      livePhase: socketState.livePhase,
      streaming: socketState.streaming,
      lastError: socketState.lastError,
      proactiveNotifications: socketState.proactiveNotifications,
      visibleProactiveNotification: socketState.visibleProactiveNotification,
      dismissProactiveNotification: socketState.dismissProactiveNotification,
      sendMessage: socketState.sendMessage,
      clearConversation: socketState.clearConversation,
      switchSession,
      createSession,
      renameSession: sessionState.renameSession,
      deleteSession,
      fillAndSend,
    }),
    [
      createSession,
      deleteSession,
      fillAndSend,
      input,
      loggedIn,
      preferences,
      sessionState,
      socketState,
      switchSession,
    ],
  );

  return <AgentChatContext.Provider value={value}>{children}</AgentChatContext.Provider>;
}

export function useAgentChatContext() {
  const context = useContext(AgentChatContext);
  if (!context) {
    throw new Error("useAgentChatContext 必须在 AgentChatProvider 内使用");
  }
  return context;
}
