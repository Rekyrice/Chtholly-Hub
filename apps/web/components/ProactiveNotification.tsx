"use client";

import { X } from "lucide-react";
import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
} from "react";
import { useAgentChatContext } from "@/components/agent/AgentChatProvider";
import { ChthollyIllustration } from "@/components/site/ChthollyIllustration";
import type { ProactiveNotificationItem } from "@/lib/types/agent";

export function ProactiveNotification() {
  const { visibleProactiveNotification, dismissProactiveNotification } = useAgentChatContext();

  if (!visibleProactiveNotification) return null;

  const notificationKey = [
    visibleProactiveNotification.timestamp,
    visibleProactiveNotification.type,
    visibleProactiveNotification.channel ?? "",
    visibleProactiveNotification.message,
  ].join("\u0000");

  return (
    <ProactiveNotificationCard
      key={notificationKey}
      notification={visibleProactiveNotification}
      onDismiss={dismissProactiveNotification}
    />
  );
}

type ProactiveNotificationCardProps = {
  notification: ProactiveNotificationItem;
  onDismiss: () => void;
};

type MessageStyle = CSSProperties & {
  "--proactive-notification-collapsed-height"?: string;
};

function ProactiveNotificationCard({
  notification,
  onDismiss,
}: ProactiveNotificationCardProps) {
  const messageId = useId();
  const messageRef = useRef<HTMLParagraphElement>(null);
  const [collapsedHeight, setCollapsedHeight] = useState<number>();
  const [expanded, setExpanded] = useState(false);
  const [overflowing, setOverflowing] = useState(false);
  const [interacted, setInteracted] = useState(false);

  const measureOverflow = useCallback(() => {
    const messageElement = messageRef.current;
    if (!messageElement) return;

    const lineHeight = Number.parseFloat(window.getComputedStyle(messageElement).lineHeight);
    if (!Number.isFinite(lineHeight) || lineHeight <= 0) {
      setOverflowing(false);
      return;
    }

    const fourLineHeight = lineHeight * 4;
    setCollapsedHeight(fourLineHeight);
    setOverflowing(messageElement.scrollHeight > fourLineHeight);
  }, []);

  useLayoutEffect(() => {
    measureOverflow();

    if (typeof ResizeObserver === "undefined" || !messageRef.current) return undefined;

    const observer = new ResizeObserver(measureOverflow);
    observer.observe(messageRef.current);
    return () => observer.disconnect();
  }, [measureOverflow]);

  useEffect(() => {
    if (interacted) return undefined;

    const timer = window.setTimeout(onDismiss, 8000);
    return () => window.clearTimeout(timer);
  }, [interacted, onDismiss]);

  const markInteracted = useCallback(() => {
    setInteracted(true);
  }, []);

  const toggleExpanded = () => {
    markInteracted();
    setExpanded((current) => !current);
  };

  const messageStyle: MessageStyle | undefined =
    collapsedHeight === undefined
      ? undefined
      : { "--proactive-notification-collapsed-height": `${collapsedHeight}px` };

  return (
    <div
      className="proactive-notification"
      role="alert"
      aria-live="polite"
      onFocusCapture={markInteracted}
    >
      <ChthollyIllustration size="xs" state="speaking" className="proactive-notification__avatar" />
      <div className="proactive-notification__content">
        <p
          ref={messageRef}
          id={messageId}
          className={`proactive-notification__message proactive-notification__message--${
            expanded ? "expanded" : "collapsed"
          }`}
          style={messageStyle}
          tabIndex={overflowing ? 0 : undefined}
          onScroll={markInteracted}
        >
          {notification.message}
        </p>
        {overflowing && (
          <div className="proactive-notification__controls">
            <button
              type="button"
              className="proactive-notification__toggle"
              aria-expanded={expanded}
              aria-controls={messageId}
              onClick={toggleExpanded}
            >
              {expanded ? "收起" : "展开"}
            </button>
          </div>
        )}
      </div>
      <button
        type="button"
        className="proactive-notification__close"
        onClick={onDismiss}
        aria-label="关闭"
      >
        <X aria-hidden size={16} />
      </button>
    </div>
  );
}
