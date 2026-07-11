import { afterEach, describe, expect, it, vi } from "vitest";
import {
  emitAuthChange,
  getServerAuthSnapshot,
  subscribeAuth,
} from "@/lib/auth/auth-store";
import { AUTH_TOKENS_KEY } from "@/lib/auth/tokens";

describe("auth store", () => {
  afterEach(() => {
    localStorage.clear();
  });

  it("notifies a subscriber once when an auth change is emitted", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeAuth(listener);

    emitAuthChange();

    expect(listener).toHaveBeenCalledTimes(1);
    unsubscribe();
  });

  it("stops notifying after unsubscribe", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeAuth(listener);
    unsubscribe();

    emitAuthChange();

    expect(listener).not.toHaveBeenCalled();
  });

  it("notifies for auth-key and cleared storage events only", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeAuth(listener);

    window.dispatchEvent(new StorageEvent("storage", { key: AUTH_TOKENS_KEY }));
    window.dispatchEvent(new StorageEvent("storage", { key: "unrelated" }));
    window.dispatchEvent(new StorageEvent("storage", { key: null }));

    expect(listener).toHaveBeenCalledTimes(2);
    unsubscribe();
  });

  it("provides a stable server snapshot", () => {
    expect(getServerAuthSnapshot()).toBeNull();
    expect(getServerAuthSnapshot()).toBe(getServerAuthSnapshot());
  });
});
