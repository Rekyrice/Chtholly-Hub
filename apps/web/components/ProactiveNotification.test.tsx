import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ProactiveNotification } from "@/components/ProactiveNotification";

const chat = vi.hoisted(() => ({
  notification: null as {
    instanceId: string;
    type: string;
    message: string;
    timestamp: string;
  } | null,
  dismiss: vi.fn(),
}));

vi.mock("@/components/agent/AgentChatProvider", () => ({
  useAgentChatContext: () => ({
    visibleProactiveNotification: chat.notification,
    dismissProactiveNotification: chat.dismiss,
  }),
}));

vi.mock("@/components/site/ChthollyIllustration", () => ({
  ChthollyIllustration: () => <div data-testid="notification-avatar" />,
}));

const shortMessage = "今天也要记得休息一下。";
const longMessage =
  "这是一条需要完整保留的超长主动通知。它不会被字符截断，即使默认只展示四行，用户也仍然可以在消息区域内部滚动阅读全文，并在需要更大阅读空间时展开通知。";

let measuredScrollHeight = 0;
let computedLineHeight = "20px";
let nextInstanceId = 0;
let resizeCallbacks: ResizeObserverCallback[] = [];

class ResizeObserverMock {
  readonly observe = vi.fn();
  readonly unobserve = vi.fn();
  readonly disconnect = vi.fn();

  constructor(callback: ResizeObserverCallback) {
    resizeCallbacks.push(callback);
  }
}

function setNotification(
  message: string,
  timestamp = "2026-07-27T08:00:00.000Z",
  instanceId = `notification-${++nextInstanceId}`,
) {
  chat.notification = {
    instanceId,
    type: "thought",
    message,
    timestamp,
  };
}

function triggerResize() {
  act(() => {
    resizeCallbacks.forEach((callback) => callback([], {} as ResizeObserver));
  });
}

describe("ProactiveNotification", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    chat.notification = null;
    chat.dismiss.mockReset();
    measuredScrollHeight = 0;
    computedLineHeight = "20px";
    nextInstanceId = 0;
    resizeCallbacks = [];

    Object.defineProperty(HTMLElement.prototype, "scrollHeight", {
      configurable: true,
      get: () => measuredScrollHeight,
    });
    const getComputedStyle = window.getComputedStyle.bind(window);
    vi.spyOn(window, "getComputedStyle").mockImplementation((element, pseudoElement) => {
      const style = getComputedStyle(element, pseudoElement);
      Object.defineProperty(style, "lineHeight", {
        configurable: true,
        value: computedLineHeight,
      });
      return style;
    });
    Object.defineProperty(globalThis, "ResizeObserver", {
      configurable: true,
      writable: true,
      value: ResizeObserverMock,
    });
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
    delete (globalThis as { ResizeObserver?: typeof ResizeObserver }).ResizeObserver;
    delete (HTMLElement.prototype as { scrollHeight?: number }).scrollHeight;
  });

  it("does not show an expand control when the message fits within four lines", () => {
    measuredScrollHeight = 60;
    setNotification(shortMessage);

    render(<ProactiveNotification />);

    expect(screen.getByText(shortMessage)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "展开" })).not.toBeInTheDocument();
  });

  it("keeps a long message complete and makes the collapsed message internally scrollable", () => {
    measuredScrollHeight = 140;
    setNotification(longMessage);

    render(<ProactiveNotification />);

    const message = screen.getByText(longMessage);
    expect(message.textContent).toBe(longMessage);
    expect(message).toHaveClass(
      "proactive-notification__message",
      "proactive-notification__message--collapsed",
    );
    expect(message).toHaveAttribute("tabindex", "0");
    expect(message).toHaveStyle("--proactive-notification-collapsed-height: 80px");
    expect(screen.getByRole("button", { name: "展开" })).toHaveAttribute(
      "aria-controls",
      message.id,
    );
  });

  it("expands and collapses a long message through an accessible control", () => {
    measuredScrollHeight = 140;
    setNotification(longMessage);

    render(<ProactiveNotification />);

    const message = screen.getByText(longMessage);
    const expand = screen.getByRole("button", { name: "展开" });
    expect(expand).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(expand);

    expect(message).toHaveClass("proactive-notification__message--expanded");
    const collapse = screen.getByRole("button", { name: "收起" });
    expect(collapse).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(collapse);

    expect(message).toHaveClass("proactive-notification__message--collapsed");
    expect(screen.getByRole("button", { name: "展开" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
  });

  it("automatically dismisses an untouched notification after eight seconds", () => {
    measuredScrollHeight = 60;
    setNotification(shortMessage);
    render(<ProactiveNotification />);

    act(() => vi.advanceTimersByTime(7999));
    expect(chat.dismiss).not.toHaveBeenCalled();

    act(() => vi.advanceTimersByTime(1));
    expect(chat.dismiss).toHaveBeenCalledTimes(1);
  });

  it("does not automatically dismiss after the user scrolls the message", () => {
    measuredScrollHeight = 140;
    setNotification(longMessage);
    render(<ProactiveNotification />);

    fireEvent.scroll(screen.getByText(longMessage));
    act(() => vi.advanceTimersByTime(8000));

    expect(chat.dismiss).not.toHaveBeenCalled();
  });

  it("synchronously cancels auto-dismiss when scrolling at the timer boundary", () => {
    measuredScrollHeight = 140;
    setNotification(longMessage);
    render(<ProactiveNotification />);
    const message = screen.getByText(longMessage);

    act(() => vi.advanceTimersByTime(7999));
    act(() => {
      message.dispatchEvent(new Event("scroll", { bubbles: true }));
      vi.advanceTimersByTime(1);
    });

    expect(chat.dismiss).not.toHaveBeenCalled();
  });

  it("does not automatically dismiss after a notification control receives focus", () => {
    measuredScrollHeight = 60;
    setNotification(shortMessage);
    render(<ProactiveNotification />);

    fireEvent.focus(screen.getByRole("button", { name: "关闭" }));
    act(() => vi.advanceTimersByTime(8000));

    expect(chat.dismiss).not.toHaveBeenCalled();
  });

  it("does not automatically dismiss after the user expands the message", () => {
    measuredScrollHeight = 140;
    setNotification(longMessage);
    render(<ProactiveNotification />);

    fireEvent.click(screen.getByRole("button", { name: "展开" }));
    act(() => vi.advanceTimersByTime(8000));

    expect(chat.dismiss).not.toHaveBeenCalled();
  });

  it("synchronously cancels auto-dismiss when expanding at the timer boundary", () => {
    measuredScrollHeight = 140;
    setNotification(longMessage);
    render(<ProactiveNotification />);
    const expand = screen.getByRole("button", { name: "展开" });

    act(() => vi.advanceTimersByTime(7999));
    act(() => {
      expand.click();
      vi.advanceTimersByTime(1);
    });

    expect(chat.dismiss).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "收起" })).toBeInTheDocument();
  });

  it("resets expansion and interaction state when a new notification arrives", () => {
    measuredScrollHeight = 140;
    setNotification(longMessage);
    const { rerender } = render(<ProactiveNotification />);

    fireEvent.click(screen.getByRole("button", { name: "展开" }));
    expect(screen.getByRole("button", { name: "收起" })).toBeInTheDocument();

    setNotification(
      `${longMessage} 第二条通知。`,
      "2026-07-27T08:01:00.000Z",
    );
    rerender(<ProactiveNotification />);

    expect(screen.getByRole("button", { name: "展开" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    act(() => vi.advanceTimersByTime(8000));
    expect(chat.dismiss).toHaveBeenCalledTimes(1);
  });

  it("remounts for a new instance with identical business fields without writing scrollTop", () => {
    measuredScrollHeight = 140;
    setNotification(longMessage, "2026-07-27T08:00:00.000Z", "notification-1");
    const { rerender } = render(<ProactiveNotification />);

    const previousMessage = screen.getByText(longMessage);
    const scrollTopSetter = vi.fn();
    Object.defineProperty(previousMessage, "scrollTop", {
      configurable: true,
      get: () => 48,
      set: scrollTopSetter,
    });
    fireEvent.scroll(previousMessage);
    fireEvent.click(screen.getByRole("button", { name: "展开" }));
    expect(screen.getByRole("button", { name: "收起" })).toBeInTheDocument();

    chat.notification = {
      ...chat.notification!,
      instanceId: "notification-2",
    };
    rerender(<ProactiveNotification />);

    const nextMessage = screen.getByText(longMessage);
    expect(nextMessage).not.toBe(previousMessage);
    expect(scrollTopSetter).not.toHaveBeenCalled();
    expect(nextMessage.scrollTop).toBe(0);
    expect(screen.getByRole("button", { name: "展开" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    act(() => vi.advanceTimersByTime(7999));
    expect(chat.dismiss).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(chat.dismiss).toHaveBeenCalledTimes(1);
  });

  it("remeasures overflow when ResizeObserver reports a size change", () => {
    measuredScrollHeight = 60;
    setNotification(longMessage);
    render(<ProactiveNotification />);

    expect(screen.queryByRole("button", { name: "展开" })).not.toBeInTheDocument();

    measuredScrollHeight = 140;
    triggerResize();

    expect(screen.getByRole("button", { name: "展开" })).toBeInTheDocument();
  });

  it("allows for whole-pixel scrollHeight rounding at exactly four fractional-height lines", () => {
    computedLineHeight = "24.225px";
    measuredScrollHeight = 97;
    setNotification(longMessage);
    render(<ProactiveNotification />);

    expect(screen.queryByRole("button", { name: "展开" })).not.toBeInTheDocument();

    measuredScrollHeight = 98;
    triggerResize();

    expect(screen.getByRole("button", { name: "展开" })).toBeInTheDocument();
  });

  it("still measures once when ResizeObserver is unavailable", () => {
    delete (globalThis as { ResizeObserver?: typeof ResizeObserver }).ResizeObserver;
    measuredScrollHeight = 140;
    setNotification(longMessage);

    expect(() => render(<ProactiveNotification />)).not.toThrow();
    expect(screen.getByRole("button", { name: "展开" })).toBeInTheDocument();
  });
});
