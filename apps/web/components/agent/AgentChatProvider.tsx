"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useAgentPreferences } from "@/components/agent/hooks/useAgentPreferences";
import { useAgentSessions } from "@/components/agent/hooks/useAgentSessions";
import { useAgentWebSocket } from "@/components/agent/hooks/useAgentWebSocket";
import type {
  AgentSessionContext,
  AgentSessionRecord,
} from "@/lib/agent/sessions";
import { isLoggedIn, purgeExpiredAuth } from "@/lib/auth/tokens";
import { agentService } from "@/lib/services/agentService";
import type {
  AgentSendOptions,
  ChatMessage,
  ProactiveNotificationItem,
} from "@/lib/types/agent";
import type { AgentLivePhase } from "@/lib/types/live2d";

type BooleanStateAction = boolean | ((previous: boolean) => boolean);

type AgentChatContextValue = {
  loggedIn: boolean;
  activeSessionId: string;
  activeSessionContext: AgentSessionContext | null;
  sessions: AgentSessionRecord[];
  messages: ChatMessage[];
  input: string;
  setInput: (value: string) => void;
  connected: boolean;
  busy: boolean;
  turnActive: boolean;
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
  sendMessage: (text: string, options?: AgentSendOptions) => Promise<void>;
  clearConversation: () => Promise<boolean>;
  clearingConversation: boolean;
  conversationClearError: string | null;
  sessionOperationPending: boolean;
  switchSession: (sessionId: string) => void;
  createSession: () => string;
  activateContextSession: (context: AgentSessionContext) => string;
  renameSession: (sessionId: string, title: string) => void;
  deleteSession: (sessionId: string) => Promise<void>;
  deletingSessionIds: string[];
  sessionDeleteError: string | null;
  fillAndSend: (text: string) => void;
};

const AgentChatContext = createContext<AgentChatContextValue | null>(null);

export function AgentChatProvider({ children }: { children: ReactNode }) {
  const [loggedIn, setLoggedIn] = useState(false);
  const [input, setInput] = useState("");
  const [deletingSessionIds, setDeletingSessionIds] = useState<string[]>([]);
  const [sessionDeleteError, setSessionDeleteError] = useState<string | null>(null);
  const [clearingConversation, setClearingConversation] = useState(false);
  const [conversationClearError, setConversationClearError] = useState<string | null>(null);
  const deletingSessionIdsRef = useRef(new Set<string>());
  const clearingConversationSessionIdRef = useRef<string | null>(null);
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
    activeSessionContextRef: sessionState.activeSessionContextRef,
    messages: sessionState.messages,
    setMessages: sessionState.setMessages,
    onInputConsumed: consumeInput,
  });

  const prepareSessionMigration = useCallback(() => {
    setInput("");
    socketState.abandonCurrentTurn();
  }, [socketState]);

  const clearSessionErrors = useCallback(() => {
    setSessionDeleteError(null);
    setConversationClearError(null);
  }, []);

  const switchSession = useCallback(
    (sessionId: string) => {
      if (clearingConversationSessionIdRef.current) return;
      if (deletingSessionIdsRef.current.has(sessionId)) return;
      if (
        sessionId === sessionState.activeSessionId
        || !sessionState.sessions.some((session) => session.id === sessionId)
      ) return;
      clearSessionErrors();
      prepareSessionMigration();
      sessionState.switchSession(sessionId);
    },
    [clearSessionErrors, prepareSessionMigration, sessionState],
  );

  const createSession = useCallback(() => {
    if (clearingConversationSessionIdRef.current) {
      return sessionState.activeSessionIdRef.current;
    }
    clearSessionErrors();
    prepareSessionMigration();
    const id = sessionState.createSession();
    return id;
  }, [clearSessionErrors, prepareSessionMigration, sessionState]);

  const activateContextSession = useCallback(
    (context: AgentSessionContext) => {
      if (clearingConversationSessionIdRef.current) {
        return sessionState.activeSessionIdRef.current;
      }
      const existing = sessionState.sessions.find(
        (session) => session.contextKey === context.contextKey,
      );
      if (existing && deletingSessionIdsRef.current.has(existing.id)) {
        return sessionState.activeSessionIdRef.current;
      }
      clearSessionErrors();
      if (existing?.id !== sessionState.activeSessionId) prepareSessionMigration();
      return sessionState.activateContextSession(context);
    },
    [clearSessionErrors, prepareSessionMigration, sessionState],
  );

  const sendMessage = useCallback(
    async (text: string, options?: AgentSendOptions) => {
      const sessionId = sessionState.activeSessionIdRef.current;
      if (
        deletingSessionIdsRef.current.has(sessionId)
        || clearingConversationSessionIdRef.current === sessionId
      ) return;
      clearSessionErrors();
      await socketState.sendMessage(text, options);
    },
    [clearSessionErrors, sessionState.activeSessionIdRef, socketState],
  );

  const deleteSession = useCallback(
    async (sessionId: string) => {
      if (!sessionState.sessions.some((session) => session.id === sessionId)) return;
      if (deletingSessionIdsRef.current.has(sessionId)) return;
      if (clearingConversationSessionIdRef.current === sessionId) return;
      const deletingActive = sessionId === sessionState.activeSessionIdRef.current;
      if (deletingActive && socketState.hasActiveTurn()) return;

      deletingSessionIdsRef.current.add(sessionId);
      setDeletingSessionIds([...deletingSessionIdsRef.current]);
      setSessionDeleteError(null);
      try {
        await agentService.clearSessionMemory(sessionId);
        if (sessionId === sessionState.activeSessionIdRef.current) {
          prepareSessionMigration();
        }
        sessionState.deleteSession(sessionId);
      } catch {
        setSessionDeleteError("未能删除会话，后端记忆清理失败，请重试。");
      } finally {
        deletingSessionIdsRef.current.delete(sessionId);
        setDeletingSessionIds([...deletingSessionIdsRef.current]);
      }
    },
    [prepareSessionMigration, sessionState, socketState],
  );

  const renameSession = useCallback((sessionId: string, title: string) => {
    if (
      deletingSessionIdsRef.current.has(sessionId)
      || clearingConversationSessionIdRef.current === sessionId
    ) return;
    sessionState.renameSession(sessionId, title);
  }, [sessionState]);

  const clearConversation = useCallback(async () => {
    const sessionId = sessionState.activeSessionIdRef.current;
    if (
      !sessionId
      || socketState.hasActiveTurn()
      || deletingSessionIdsRef.current.has(sessionId)
      || clearingConversationSessionIdRef.current !== null
    ) return false;

    clearingConversationSessionIdRef.current = sessionId;
    setClearingConversation(true);
    setConversationClearError(null);
    try {
      await agentService.clearSessionMemory(sessionId);
      if (sessionState.activeSessionIdRef.current !== sessionId) {
        setConversationClearError("会话已经切换，请重试清空操作。");
        return false;
      }
      socketState.clearLocalConversation();
      return true;
    } catch {
      setConversationClearError("未能清空当前对话，后端记忆清理失败，请重试。");
      return false;
    } finally {
      clearingConversationSessionIdRef.current = null;
      setClearingConversation(false);
    }
  }, [sessionState.activeSessionIdRef, socketState]);

  const fillAndSend = useCallback(
    (text: string) => {
      setInput(text);
      void sendMessage(text);
    },
    [sendMessage],
  );

  const value = useMemo<AgentChatContextValue>(
    () => {
      const sessionOperationPending = clearingConversation
        || deletingSessionIds.includes(sessionState.activeSessionId);
      return ({
      loggedIn,
      activeSessionId: sessionState.activeSessionId,
      activeSessionContext: sessionState.activeSessionContextRef.current,
      sessions: sessionState.sessions,
      messages: sessionState.messages,
      input,
      setInput,
      connected: socketState.connected,
      busy: socketState.busy,
      turnActive: socketState.turnActive,
      ...preferences,
      liveSteps: socketState.liveSteps,
      livePhase: socketState.livePhase,
      streaming: socketState.streaming,
      lastError: socketState.lastError,
      proactiveNotifications: socketState.proactiveNotifications,
      visibleProactiveNotification: socketState.visibleProactiveNotification,
      dismissProactiveNotification: socketState.dismissProactiveNotification,
      sendMessage,
      clearConversation,
      clearingConversation,
      conversationClearError,
      sessionOperationPending,
      switchSession,
      createSession,
      activateContextSession,
      renameSession,
      deleteSession,
      deletingSessionIds,
      sessionDeleteError,
      fillAndSend,
      });
    },
    [
      clearConversation,
      clearingConversation,
      conversationClearError,
      createSession,
      deleteSession,
      deletingSessionIds,
      activateContextSession,
      fillAndSend,
      input,
      loggedIn,
      preferences,
      renameSession,
      sendMessage,
      sessionDeleteError,
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
