import { afterEach, describe, expect, it, vi } from "vitest";
import {
  emitAuthChange,
  getServerAuthSnapshot,
  loadCurrentUserOnce,
  subscribeAuth,
} from "@/lib/auth/auth-store";
import { AUTH_TOKENS_KEY } from "@/lib/auth/tokens";
import type { AuthUser } from "@/lib/types/auth";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

const user: AuthUser = { id: 1, nickname: "Chtholly" };

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

  it("shares one current-user request for concurrent callers with the same access token", async () => {
    const request = deferred<AuthUser>();
    const loader = vi.fn(() => request.promise);

    const first = loadCurrentUserOnce("same-token", loader);
    const second = loadCurrentUserOnce("same-token", loader);

    expect(loader).toHaveBeenCalledTimes(1);
    expect(second).toBe(first);
    request.resolve(user);
    await expect(Promise.all([first, second])).resolves.toEqual([user, user]);
  });

  it("does not share current-user requests across access tokens", async () => {
    const firstLoader = vi.fn(async () => user);
    const secondUser = { ...user, id: 2 };
    const secondLoader = vi.fn(async () => secondUser);

    await expect(
      Promise.all([
        loadCurrentUserOnce("token-a", firstLoader),
        loadCurrentUserOnce("token-b", secondLoader),
      ]),
    ).resolves.toEqual([user, secondUser]);
    expect(firstLoader).toHaveBeenCalledTimes(1);
    expect(secondLoader).toHaveBeenCalledTimes(1);
  });

  it("clears a completed current-user request so the token can be loaded again", async () => {
    const loader = vi.fn(async () => user);

    await loadCurrentUserOnce("completed-token", loader);
    await loadCurrentUserOnce("completed-token", loader);

    expect(loader).toHaveBeenCalledTimes(2);
  });

  it("clears a failed current-user request so the token can be retried", async () => {
    const loader = vi
      .fn<() => Promise<AuthUser>>()
      .mockRejectedValueOnce(new Error("network failure"))
      .mockResolvedValueOnce(user);

    await expect(loadCurrentUserOnce("failed-token", loader)).rejects.toThrow("network failure");
    await expect(loadCurrentUserOnce("failed-token", loader)).resolves.toBe(user);
    expect(loader).toHaveBeenCalledTimes(2);
  });
});
