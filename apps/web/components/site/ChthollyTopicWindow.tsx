import type { TopicCluster } from "@/lib/types/topic";

export default function ChthollyTopicWindow({ topics }: { topics: TopicCluster[] }) {
  if (topics.length === 0) {
    return <p className="room-muted">窗边暂时没有新的话题。</p>;
  }

  return (
    <ul className="room-topic-list">
      {topics.slice(0, 3).map((topic) => (
        <li key={topic.topicName}>
          <div>
            <strong>{topic.topicName}</strong>
            <span>{topic.size} 篇相关内容</span>
          </div>
          <p>{topic.summary}</p>
        </li>
      ))}
    </ul>
  );
}
