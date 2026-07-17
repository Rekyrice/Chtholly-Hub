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

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  async function retryOverview() {
    if (isRetrying) return;

    const requestId = ++requestIdRef.current;
    setIsRetrying(true);

    try {
      const nextOverview = await topicService.overview();
      if (mountedRef.current && requestId === requestIdRef.current) {
        setOverview(nextOverview);
      }
    } catch {
      if (mountedRef.current && requestId === requestIdRef.current) {
        setOverview({
          items: [],
          state: "FAILED",
          windowDays: 7,
          reason: "REQUEST_FAILED",
        });
      }
    } finally {
      if (mountedRef.current && requestId === requestIdRef.current) {
        setIsRetrying(false);
      }
    }
  }

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
    <div className="room-topic-state room-topic-state--failed" aria-live="polite">
      <p>话题整理暂时没有完成</p>
      <button type="button" disabled={isRetrying} onClick={retryOverview}>
        {isRetrying ? "重新查看中…" : "重新查看"}
      </button>
    </div>
  );
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
