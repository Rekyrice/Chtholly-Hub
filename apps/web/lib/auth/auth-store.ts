"use client";

import { useMemo, useSyncExternalStore } from "react";
import { AUTH_TOKENS_KEY, isAccessTokenValid } from "@/lib/auth/tokens";
import { authService } from "@/lib/services/authService";
import type { AuthUser, StoredAuth } from "@/lib/types/auth";

export const AUTH_CHANGE_EVENT = "chtholly-auth-change";

const currentUserRequests = new Map<string, Promise<AuthUser>>();

export function loadCurrentUserOnce(
  accessToken: string,
  loader: () => Promise<AuthUser> = () => authService.me(accessToken),
): Promise<AuthUser> {
  const existing = currentUserRequests.get(accessToken);
  if (existing) return existing;

  const request = loader().finally(() => {
    if (currentUserRequests.get(accessToken) === request) {
      currentUserRequests.delete(accessToken);
    }
  });
  currentUserRequests.set(accessToken, request);
  return request;
}

export function getAuthSnapshot(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(AUTH_TOKENS_KEY);
}

export function getServerAuthSnapshot(): null {
  return null;
}

export function subscribeAuth(onStoreChange: () => void): () => void {
  if (typeof window === "undefined") return () => undefined;

  const onStorage = (event: StorageEvent) => {
    if (event.key === AUTH_TOKENS_KEY || event.key === null) {
      onStoreChange();
    }
  };

  window.addEventListener(AUTH_CHANGE_EVENT, onStoreChange);
  window.addEventListener("storage", onStorage);
  return () => {
    window.removeEventListener(AUTH_CHANGE_EVENT, onStoreChange);
    window.removeEventListener("storage", onStorage);
  };
}

export function emitAuthChange(): void {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(AUTH_CHANGE_EVENT));
  }
}

export function useAuthSnapshot(): string | null {
  return useSyncExternalStore(subscribeAuth, getAuthSnapshot, getServerAuthSnapshot);
}

export function useStoredAuth(): StoredAuth | null {
  const snapshot = useAuthSnapshot();
  return useMemo(() => {
    if (!snapshot) return null;
    try {
      const auth = JSON.parse(snapshot) as StoredAuth;
      return isAccessTokenValid(auth) ? auth : null;
    } catch {
      return null;
    }
  }, [snapshot]);
}

export function useAuthUser(): AuthUser | null {
  return useStoredAuth()?.user ?? null;
}
