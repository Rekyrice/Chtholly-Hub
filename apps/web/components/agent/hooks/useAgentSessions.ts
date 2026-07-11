"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  createSessionId,
  loadActiveSessionId,
  loadStoredSessions,
  saveActiveSessionId,
  saveStoredSessions,
  sessionTitleFromMessages,
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

function emptySession(id = createSessionId()): AgentSessionRecord {
  const now = Date.now();
  return { id, title: NEW_SESSION_TITLE, messages: [], createdAt: now, updatedAt: now };
}

export function useAgentSessions() {
  const [sessions, setSessions] = useState<AgentSessionRecord[]>([]);
  const [activeSessionId, setActiveSessionId] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [hydrated, setHydrated] = useState(false);
  const activeSessionIdRef = useRef(activeSessionId);
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
    setMessages(initialSessions.find((session) => session.id === activeId)?.messages ?? []);
    /* eslint-enable react-hooks/set-state-in-effect */
    saveActiveSessionId(activeId);
    setHydrated(true);
  }, []);

  const persistActiveSession = useCallback((nextMessages: ChatMessage[]) => {
    const id = activeSessionIdRef.current;
    if (!id) return;
    setSessions((previous) => {
      const existing = previous.find((session) => session.id === id);
      const record: AgentSessionRecord = {
        id,
        title: existing?.titleLocked ? existing.title : sessionTitleFromMessages(nextMessages),
        messages: nextMessages,
        createdAt: existing?.createdAt ?? Date.now(),
        updatedAt: Date.now(),
        titleLocked: existing?.titleLocked,
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
    return record.id;
  }, [persistActiveSession]);

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
    switchSession,
    createSession,
    renameSession,
    deleteSession,
  } as const;
}
