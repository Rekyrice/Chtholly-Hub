import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DeferredAgentRuntime from "@/components/agent/DeferredAgentRuntime";
import { emitAuthChange } from "@/lib/auth/auth-store";
import { AUTH_TOKENS_KEY } from "@/lib/auth/tokens";

const navigation = vi.hoisted(() => ({ pathname: "/hub" }));
const dynamicRuntime = vi.hoisted(() => ({ throws: false }));

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
}));

vi.mock("next/dynamic", () => ({
  default: () => function AuthenticatedRuntimeStub() {
    if (dynamicRuntime.throws) throw new Error("chunk failed");
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
    dynamicRuntime.throws = false;
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

  it("contains a failed optional runtime and retries after the path changes", () => {
    dynamicRuntime.throws = true;
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

    dynamicRuntime.throws = false;
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
});
