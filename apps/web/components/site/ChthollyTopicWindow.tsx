"use client";

import { useEffect, useRef, useState } from "react";
import { topicService } from "@/lib/services/topicService";
import type { TagItem } from "@/lib/types/tag";
import type { TopicCluster, TopicOverview } from "@/lib/types/topic";

type ChthollyTopicWindowProps = {
  initialOverview: TopicOverview;
  signals: TagItem[];
};

export default function ChthollyTopicWindow({
  initialOverview,
  signals,
}: ChthollyTopicWindowProps) {
  const [overview, setOverview] = useState(initialOverview);
  const [isRetrying, setIsRetrying] = useState(false);
  const mountedRef = useRef(true);
  const requestIdRef = useRef(0);
  const resultRef = useRef<HTMLDivElement>(null);
  const shouldFocusResultRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    requestIdRef.current += 1;
    shouldFocusResultRef.current = false;
    /* eslint-disable react-hooks/set-state-in-effect -- incoming server data replaces the retry island snapshot */
    setOverview(initialOverview);
    setIsRetrying(false);
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [initialOverview]);

  useEffect(() => {
    if (isRetrying) return;

    if (shouldFocusResultRef.current) {
      shouldFocusResultRef.current = false;
      resultRef.current?.focus();
    }
  }, [isRetrying, overview]);

  async function retryOverview() {
    if (isRetrying) return;

    const requestId = ++requestIdRef.current;
    setIsRetrying(true);

    try {
      const nextOverview = await topicService.overview();
      if (mountedRef.current && requestId === requestIdRef.current) {
        shouldFocusResultRef.current = true;
        setOverview(nextOverview);
      }
    } catch {
      if (mountedRef.current && requestId === requestIdRef.current) {
        shouldFocusResultRef.current = true;
        setOverview({
          items: [],
          state: "FAILED",
          windowDays: overview.windowDays,
          reason: "REQUEST_FAILED",
        });
      }
    } finally {
      if (mountedRef.current && requestId === requestIdRef.current) {
        setIsRetrying(false);
      }
    }
  }

  return (
    <div
      ref={resultRef}
      className="room-topic-result"
      role="region"
      aria-label="话题整理结果"
      aria-busy={isRetrying}
      data-window-days={overview.windowDays}
      tabIndex={-1}
    >
      <span className="sr-only" role="status" aria-live="polite">
        {isRetrying ? "正在重新查看话题整理结果" : getOverviewAnnouncement(overview)}
      </span>
      <OverviewContent
        overview={overview}
        signals={signals}
        isRetrying={isRetrying}
        onRetry={retryOverview}
      />
    </div>
  );
}

function OverviewContent({
  overview,
  signals,
  isRetrying,
  onRetry,
}: {
  overview: TopicOverview;
  signals: TagItem[];
  isRetrying: boolean;
  onRetry: () => void;
}) {
  if (overview.state === "READY") {
    if (overview.items.length === 0) {
      return (
        <p className="room-topic-state">
          这次整理没有留下可以展示的话题。稍后再来看看吧。
        </p>
      );
    }

    return (
      <ul className="room-topic-list">
        {overview.items.slice(0, 3).map((topic) => (
          <TopicNote key={`${topic.topicName}-${topic.clusteredAt}`} topic={topic} />
        ))}
      </ul>
    );
  }

  if (overview.state === "SPARSE") {
    const recentSignals = signals.slice(0, 6);
    return (
      <div className="room-topic-state room-topic-state--sparse">
        <p>近几天还没有形成稳定的话题</p>
        {recentSignals.length > 0 && (
          <div className="room-topic-signals">
            <span>近期标签</span>
            <ul>
              {recentSignals.map((signal) => (
                <li key={signal.id || signal.slug}>{signal.name}</li>
              ))}
            </ul>
          </div>
        )}
      </div>
    );
  }

  if (overview.state === "PENDING") {
    return <p className="room-topic-state">正在整理近期内容</p>;
  }

  return (
    <div className="room-topic-state room-topic-state--failed">
      <p>话题整理暂时没有完成</p>
      <button
        type="button"
        disabled={isRetrying}
        onClick={onRetry}
      >
        {isRetrying ? "重新查看中…" : "重新查看"}
      </button>
    </div>
  );
}

function getOverviewAnnouncement(overview: TopicOverview) {
  if (overview.state === "READY") {
    return overview.items.length > 0
      ? `话题整理已完成，共 ${overview.items.length} 条结果`
      : "话题整理已完成，暂时没有结果";
  }
  if (overview.state === "SPARSE") return "话题整理完成，近期信号仍较稀疏";
  if (overview.state === "PENDING") return "话题整理仍在进行中";
  return "话题整理请求失败，可以重新查看";
}

function TopicNote({ topic }: { topic: TopicCluster }) {
  return (
    <li className="room-topic-note">
      <div className="room-topic-note__heading">
        <strong>{topic.topicName}</strong>
        <span>{topic.size} 篇相关内容</span>
      </div>
      <p>{topic.summary}</p>
      {topic.keyEntities.length > 0 && (
        <ul className="room-topic-note__entities" aria-label="关键词">
          {topic.keyEntities.slice(0, 3).map((entity, index) => (
            <li key={`${entity}-${index}`}>{entity}</li>
          ))}
        </ul>
      )}
      <time dateTime={isValidTime(topic.clusteredAt) ? topic.clusteredAt : undefined}>
        {formatTopicTime(topic.clusteredAt)}
      </time>
    </li>
  );
}

function isValidTime(value: string) {
  return !Number.isNaN(new Date(value).getTime());
}

function formatTopicTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "最近整理";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}
