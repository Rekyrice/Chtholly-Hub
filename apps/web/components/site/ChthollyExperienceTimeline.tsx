import type { AgentExperienceTimeline } from "@/lib/types/agent";

type ExperienceLike = {
  id?: string | number;
  text?: string | null;
  valueScore?: number | null;
  importance?: number | null;
  createdAt?: string | null;
  source?: string | null;
};

type DisplayExperience = {
  key: string;
  signature: string;
  text: string;
  createdAt: string;
  score: number;
  timestamp: number;
  order: number;
};

export default function ChthollyExperienceTimeline({
  timeline,
}: {
  timeline: AgentExperienceTimeline;
}) {
  const quiet = timeline.recent.some((experience) => experience.source === "community-quiet");
  const recent = timeline.recent
    .filter((experience) => experience.source !== "community-quiet")
    .map((experience, index) => normalizeExperience(experience, "recent", index))
    .filter((experience): experience is DisplayExperience => experience !== null)
    .sort(compareExperiences);
  const featured = recent[0];
  const scattered = deduplicateExperiences(
    [
      ...recent.slice(1),
      ...timeline.archived.map((experience, index) =>
        normalizeExperience(experience, `archived-${experience.id ?? index}`, recent.length + index),
      ),
    ]
      .filter((experience): experience is DisplayExperience => experience !== null)
      .filter((experience) => experience.signature !== featured?.signature)
      .sort(compareExperiences),
  );
  const visibleMemories = scattered.slice(0, 3);
  const hiddenMemories = scattered.slice(3);

  return (
    <div className="room-experience-stack">
      <article className="room-experience-featured">
        <p className="room-experience-featured__eyebrow">Tonight</p>
        <h3>今夜手记</h3>
        <p className="room-experience-featured__text">
          {featured?.text ?? "今晚没有特别需要记下的事。"}
        </p>
        {featured && <ExperienceTime value={featured.createdAt} />}
        {quiet && <p className="room-experience-quiet">今晚社区很安静</p>}
        <span className="room-experience-seal" aria-hidden="true">
          记
        </span>
      </article>

      <div className="room-experience-aside">
        <section className="room-memory-block">
          <h3>零散记忆</h3>
          {visibleMemories.length > 0 ? (
            <MemoryList items={visibleMemories} className="room-memory-timeline--primary" />
          ) : (
            <p className="room-memory-empty">暂时没有散落在书页间的记忆。</p>
          )}
          {hiddenMemories.length > 0 && (
            <details className="room-memory-more">
              <summary>还有 {hiddenMemories.length} 条记忆</summary>
              <MemoryList items={hiddenMemories} className="room-memory-timeline--more" />
            </details>
          )}
        </section>

        {timeline.weeklySummaries.length > 0 && (
          <section className="room-weekly-letters">
            <h3>本周来信</h3>
            <ul>
              {timeline.weeklySummaries.map((summary) => (
                <li key={`${summary.weekKey}-${summary.summary}`}>
                  <p>{summary.summary}</p>
                  <span>{summary.weekKey}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </div>
  );
}

function MemoryList({ items, className }: { items: DisplayExperience[]; className: string }) {
  return (
    <ul className={`room-memory-timeline ${className}`}>
      {items.map((item) => (
        <li key={item.key}>
          <p>{item.text}</p>
          <ExperienceTime value={item.createdAt} />
        </li>
      ))}
    </ul>
  );
}

function ExperienceTime({ value }: { value: string }) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return <span className="room-experience-time">时间未记下</span>;
  }

  return (
    <time className="room-experience-time" dateTime={value}>
      {new Intl.DateTimeFormat("zh-CN", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
      }).format(date)}
    </time>
  );
}

function normalizeExperience(
  experience: ExperienceLike,
  keyPrefix: string,
  order: number,
): DisplayExperience | null {
  const text = typeof experience.text === "string" ? experience.text.trim() : "";
  if (!text) return null;

  const createdAt = typeof experience.createdAt === "string" ? experience.createdAt : "";
  const source = typeof experience.source === "string" ? experience.source : "unknown";
  const importance = normalizeNumber(experience.importance);
  const valueScore = normalizeNumber(experience.valueScore);
  const parsedTime = new Date(createdAt).getTime();
  const timestamp = Number.isNaN(parsedTime) ? 0 : parsedTime;
  const signature = `${source}|${createdAt}|${text}`;

  return {
    key: `${keyPrefix}-${signature}`,
    signature,
    text,
    createdAt,
    score: importance * 100 + valueScore * 10,
    timestamp,
    order,
  };
}

function normalizeNumber(value: number | null | undefined) {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function compareExperiences(a: DisplayExperience, b: DisplayExperience) {
  return b.score - a.score || b.timestamp - a.timestamp || a.order - b.order;
}

function deduplicateExperiences(items: DisplayExperience[]) {
  const seen = new Set<string>();
  return items.filter((item) => {
    if (seen.has(item.signature)) return false;
    seen.add(item.signature);
    return true;
  });
}
