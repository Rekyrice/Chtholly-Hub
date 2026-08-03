"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  createSessionId,
  loadActiveSessionId,
  loadStoredSessions,
  saveActiveSessionId,
  saveStoredSessions,
  sessionTitleFromMessages,
  normalizeChatMessages,
  type AgentSessionContext,
  type AgentSessionRecord,
} from "@/lib/agent/sessions";
import type { ChatMessage } from "@/lib/types/agent";

const NEW_SESSION_TITLE = "新对话";

function upsertSessionRecord(
  sessions: AgentSessionRecord[],
  record: AgentSessionRecord,
): AgentSessionRecord[] {
  const index = sessions.findIndex((session) => session.id === record.id);
  if (index === -1) return [record, ...sessions];
  const next = [...sessions];
  next[index] = record;
  next.sort((a, b) => b.updatedAt - a.updatedAt);
  return next;
}

function emptySession(
  id = createSessionId(),
  context?: AgentSessionContext,
): AgentSessionRecord {
  const now = Date.now();
  return {
    id,
    title: context ? `陪读：${context.contextTitle}` : NEW_SESSION_TITLE,
    messages: [],
    createdAt: now,
    updatedAt: now,
    titleLocked: context ? true : undefined,
    contextKey: context?.contextKey,
    contextTitle: context?.contextTitle,
    postId: context?.postId,
  };
}

export function useAgentSessions() {
  const [sessions, setSessions] = useState<AgentSessionRecord[]>([]);
  const [activeSessionId, setActiveSessionId] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [hydrated, setHydrated] = useState(false);
  const activeSessionIdRef = useRef(activeSessionId);
  const activeSessionContextRef = useRef<AgentSessionContext | null>(null);
  const messagesRef = useRef(messages);
  const sessionsRef = useRef(sessions);

  useEffect(() => {
    activeSessionIdRef.current = activeSessionId;
    messagesRef.current = messages;
    sessionsRef.current = sessions;
  }, [activeSessionId, messages, sessions]);

  useEffect(() => {
    const stored = loadStoredSessions();
    let activeId = loadActiveSessionId();
    if (!activeId || !stored.some((session) => session.id === activeId)) {
      activeId = stored[0]?.id ?? createSessionId();
    }
    const initialSessions = stored.length > 0 ? stored : [emptySession(activeId)];
    /* eslint-disable react-hooks/set-state-in-effect -- sessions are hydrated from localStorage */
    setSessions(initialSessions);
    setActiveSessionId(activeId);
    const activeSession = initialSessions.find((session) => session.id === activeId);
    setMessages(activeSession?.messages ?? []);
    activeSessionContextRef.current = activeSession ? sessionContext(activeSession) : null;
    /* eslint-enable react-hooks/set-state-in-effect */
    saveActiveSessionId(activeId);
    saveStoredSessions(initialSessions);
    setHydrated(true);
  }, []);

  const persistActiveSession = useCallback((nextMessages: ChatMessage[]) => {
    const id = activeSessionIdRef.current;
    if (!id) return;
    const normalizedMessages = normalizeChatMessages(nextMessages);
    setSessions((previous) => {
      const existing = previous.find((session) => session.id === id);
      const record: AgentSessionRecord = {
        id,
        title: existing?.titleLocked ? existing.title : sessionTitleFromMessages(normalizedMessages),
        messages: normalizedMessages,
        createdAt: existing?.createdAt ?? Date.now(),
        updatedAt: Date.now(),
        titleLocked: existing?.titleLocked,
        contextKey: existing?.contextKey,
        contextTitle: existing?.contextTitle,
        postId: existing?.postId,
      };
      const next = upsertSessionRecord(previous, record);
      saveStoredSessions(next);
      return next;
    });
  }, []);

  useEffect(() => {
    if (hydrated) persistActiveSession(messages);
  }, [hydrated, messages, persistActiveSession]);

  const switchSession = useCallback(
    (sessionId: string) => {
      if (sessionId === activeSessionIdRef.current) return false;
      persistActiveSession(messagesRef.current);
      const target = sessionsRef.current.find((session) => session.id === sessionId);
      if (!target) return false;
      setActiveSessionId(sessionId);
      activeSessionIdRef.current = sessionId;
      saveActiveSessionId(sessionId);
      setMessages(target.messages);
      activeSessionContextRef.current = sessionContext(target);
      return true;
    },
    [persistActiveSession],
  );

  const createSession = useCallback(() => {
    persistActiveSession(messagesRef.current);
    const record = emptySession();
    setSessions((previous) => {
      const next = upsertSessionRecord(previous, record);
      saveStoredSessions(next);
      return next;
    });
    setActiveSessionId(record.id);
    activeSessionIdRef.current = record.id;
    saveActiveSessionId(record.id);
    setMessages([]);
    activeSessionContextRef.current = null;
    return record.id;
  }, [persistActiveSession]);

  const activateContextSession = useCallback(
    (context: AgentSessionContext) => {
      const existing = sessionsRef.current.find(
        (session) => session.contextKey === context.contextKey,
      );
      if (existing) {
        if (existing.id !== activeSessionIdRef.current) {
          switchSession(existing.id);
        } else {
          activeSessionContextRef.current = sessionContext(existing);
        }
        return existing.id;
      }

      persistActiveSession(messagesRef.current);
      const record = emptySession(undefined, context);
      const next = upsertSessionRecord(sessionsRef.current, record);
      sessionsRef.current = next;
      setSessions(next);
      saveStoredSessions(next);
      setActiveSessionId(record.id);
      activeSessionIdRef.current = record.id;
      activeSessionContextRef.current = context;
      saveActiveSessionId(record.id);
      setMessages([]);
      return record.id;
    },
    [persistActiveSession, switchSession],
  );

  const renameSession = useCallback((sessionId: string, title: string) => {
    const trimmed = title.trim();
    if (!trimmed) return;
    setSessions((previous) => {
      const next = previous.map((session) =>
        session.id === sessionId
          ? { ...session, title: trimmed, titleLocked: true, updatedAt: Date.now() }
          : session,
      );
      saveStoredSessions(next);
      return next;
    });
  }, []);

  const deleteSession = useCallback(
    (sessionId: string) => {
      const deletingActive = sessionId === activeSessionIdRef.current;
      if (deletingActive) persistActiveSession(messagesRef.current);
      let next = sessionsRef.current.filter((session) => session.id !== sessionId);
      if (next.length === 0) next = [emptySession()];
      if (deletingActive) {
        const fallback = [...next].sort((a, b) => b.updatedAt - a.updatedAt)[0];
        setActiveSessionId(fallback.id);
        activeSessionIdRef.current = fallback.id;
        saveActiveSessionId(fallback.id);
        setMessages(fallback.messages);
        activeSessionContextRef.current = sessionContext(fallback);
      }
      setSessions(next);
      saveStoredSessions(next);
      return deletingActive;
    },
    [persistActiveSession],
  );

  return {
    sessions,
    activeSessionId,
    messages,
    setMessages,
    hydrated,
    activeSessionIdRef,
    activeSessionContextRef,
    switchSession,
    createSession,
    activateContextSession,
    renameSession,
    deleteSession,
  } as const;
}

function sessionContext(session: AgentSessionRecord): AgentSessionContext | null {
  if (!session.contextKey || !session.contextTitle) return null;
  return {
    contextKey: session.contextKey,
    contextTitle: session.contextTitle,
    postId: session.postId,
  };
}
