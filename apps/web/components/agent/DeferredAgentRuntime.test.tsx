import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DeferredAgentRuntime from "@/components/agent/DeferredAgentRuntime";
import { emitAuthChange } from "@/lib/auth/auth-store";
import { AUTH_TOKENS_KEY } from "@/lib/auth/tokens";

const navigation = vi.hoisted(() => ({ pathname: "/hub" }));
const dynamicRuntime = vi.hoisted(() => ({
  error: null as Error | null,
  isLoading: false,
  renderThrows: false,
  retry: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
}));

vi.mock("next/dynamic", () => ({
  default: (
    _loader: unknown,
    options: {
      loading?: (props: {
        error: Error | null;
        isLoading: boolean;
        pastDelay: boolean;
        retry: () => void;
        timedOut: boolean;
      }) => React.ReactNode;
    },
  ) => function AuthenticatedRuntimeStub() {
    if (dynamicRuntime.renderThrows) throw new Error("runtime render failed");
    if (dynamicRuntime.error || dynamicRuntime.isLoading) {
      return options.loading?.({
        error: dynamicRuntime.error,
        isLoading: dynamicRuntime.isLoading,
        pastDelay: true,
        retry: dynamicRuntime.retry,
        timedOut: false,
      }) ?? null;
    }
    return <div data-testid="authenticated-agent-runtime" />;
  },
}));

const validStoredAuthWithoutUser = {
  accessToken: "valid-access-token",
  accessTokenExpiresAt: "2999-01-01T00:00:00.000Z",
  refreshToken: "refresh-token",
  refreshTokenExpiresAt: "2999-01-02T00:00:00.000Z",
};

describe("DeferredAgentRuntime", () => {
  beforeEach(() => {
    navigation.pathname = "/hub";
    dynamicRuntime.error = null;
    dynamicRuntime.isLoading = false;
    dynamicRuntime.renderThrows = false;
    dynamicRuntime.retry.mockReset();
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it("renders nothing while the browser has no valid stored auth", () => {
    render(<DeferredAgentRuntime />);

    expect(screen.queryByTestId("authenticated-agent-runtime")).not.toBeInTheDocument();
  });

  it("loads the authenticated runtime for a valid token even when user is absent", () => {
    const view = render(<DeferredAgentRuntime />);

    act(() => {
      localStorage.setItem(AUTH_TOKENS_KEY, JSON.stringify(validStoredAuthWithoutUser));
      emitAuthChange();
    });
    view.rerender(<DeferredAgentRuntime />);

    expect(screen.getByTestId("authenticated-agent-runtime")).toBeInTheDocument();
  });

  it("skips the deferred runtime throughout the Agent workspace", () => {
    navigation.pathname = "/agent/history";
    localStorage.setItem(AUTH_TOKENS_KEY, JSON.stringify(validStoredAuthWithoutUser));

    render(<DeferredAgentRuntime />);

    expect(screen.queryByTestId("authenticated-agent-runtime")).not.toBeInTheDocument();
  });

  it.each([
    ["damaged JSON", "{not-json"],
    ["an invalid expiry", JSON.stringify({ ...validStoredAuthWithoutUser, accessTokenExpiresAt: "not-a-date" })],
    ["an invalid token shape", JSON.stringify({ accessToken: 123 })],
  ])("does not load for %s", (_label, storedValue) => {
    localStorage.setItem(AUTH_TOKENS_KEY, storedValue);

    render(<DeferredAgentRuntime />);

    expect(screen.queryByTestId("authenticated-agent-runtime")).not.toBeInTheDocument();
  });

  it("contains a loaded runtime render failure and resets after the path changes", () => {
    dynamicRuntime.renderThrows = true;
    localStorage.setItem(AUTH_TOKENS_KEY, JSON.stringify(validStoredAuthWithoutUser));
    const errorLog = vi.spyOn(console, "error").mockImplementation(() => undefined);

    const view = render(
      <>
        <main>primary content</main>
        <DeferredAgentRuntime />
      </>,
    );

    expect(screen.getByRole("main")).toHaveTextContent("primary content");
    expect(screen.queryByTestId("authenticated-agent-runtime")).not.toBeInTheDocument();
    expect(errorLog).toHaveBeenCalled();

    dynamicRuntime.renderThrows = false;
    navigation.pathname = "/about";
    view.rerender(
      <>
        <main>primary content</main>
        <DeferredAgentRuntime />
      </>,
    );

    expect(screen.getByTestId("authenticated-agent-runtime")).toBeInTheDocument();
    errorLog.mockRestore();
  });

  it("logs a loader error once", () => {
    dynamicRuntime.error = new Error("chunk failed");
    localStorage.setItem(AUTH_TOKENS_KEY, JSON.stringify(validStoredAuthWithoutUser));
    const errorLog = vi.spyOn(console, "error").mockImplementation(() => undefined);

    const view = render(<DeferredAgentRuntime />);

    expect(errorLog).toHaveBeenCalledTimes(1);
    expect(dynamicRuntime.retry).not.toHaveBeenCalled();

    view.rerender(<DeferredAgentRuntime />);
    expect(errorLog).toHaveBeenCalledTimes(1);
    expect(dynamicRuntime.retry).not.toHaveBeenCalled();
    errorLog.mockRestore();
  });

  it("retries once when the reset key changes and not again for the same key", () => {
    dynamicRuntime.error = new Error("chunk failed");
    localStorage.setItem(AUTH_TOKENS_KEY, JSON.stringify(validStoredAuthWithoutUser));
    const errorLog = vi.spyOn(console, "error").mockImplementation(() => undefined);

    const view = render(<DeferredAgentRuntime />);
    expect(dynamicRuntime.retry).not.toHaveBeenCalled();

    navigation.pathname = "/about";
    view.rerender(<DeferredAgentRuntime />);
    expect(dynamicRuntime.retry).toHaveBeenCalledTimes(1);

    view.rerender(<DeferredAgentRuntime />);
    expect(dynamicRuntime.retry).toHaveBeenCalledTimes(1);
    errorLog.mockRestore();
  });

  it("retries when an auth change replaces the access token", () => {
    dynamicRuntime.error = new Error("chunk failed");
    localStorage.setItem(AUTH_TOKENS_KEY, JSON.stringify(validStoredAuthWithoutUser));
    const errorLog = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const view = render(<DeferredAgentRuntime />);

    act(() => {
      localStorage.setItem(
        AUTH_TOKENS_KEY,
        JSON.stringify({ ...validStoredAuthWithoutUser, accessToken: "replacement-token" }),
      );
      emitAuthChange();
    });
    view.rerender(<DeferredAgentRuntime />);

    expect(dynamicRuntime.retry).toHaveBeenCalledTimes(1);
    view.rerender(<DeferredAgentRuntime />);
    expect(dynamicRuntime.retry).toHaveBeenCalledTimes(1);
    errorLog.mockRestore();
  });

  it("logs each new loader error once", () => {
    dynamicRuntime.error = new Error("first chunk failure");
    localStorage.setItem(AUTH_TOKENS_KEY, JSON.stringify(validStoredAuthWithoutUser));
    const errorLog = vi.spyOn(console, "error").mockImplementation(() => undefined);

    const view = render(<DeferredAgentRuntime />);
    view.rerender(<DeferredAgentRuntime />);
    expect(errorLog).toHaveBeenCalledTimes(1);

    dynamicRuntime.error = new Error("second chunk failure");
    view.rerender(<DeferredAgentRuntime />);
    view.rerender(<DeferredAgentRuntime />);
    expect(errorLog).toHaveBeenCalledTimes(2);
    errorLog.mockRestore();
  });

  it("does not log or retry while the chunk is loading without an error", () => {
    dynamicRuntime.isLoading = true;
    localStorage.setItem(AUTH_TOKENS_KEY, JSON.stringify(validStoredAuthWithoutUser));
    const errorLog = vi.spyOn(console, "error").mockImplementation(() => undefined);

    render(<DeferredAgentRuntime />);

    expect(errorLog).not.toHaveBeenCalled();
    expect(dynamicRuntime.retry).not.toHaveBeenCalled();
    errorLog.mockRestore();
  });
});
