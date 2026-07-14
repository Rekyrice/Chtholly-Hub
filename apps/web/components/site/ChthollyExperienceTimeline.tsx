import { ChthollyIllustration } from "@/components/site/ChthollyIllustration";
import type { AgentExperienceTimeline } from "@/lib/types/agent";

export default function ChthollyExperienceTimeline({
  timeline,
}: {
  timeline: AgentExperienceTimeline;
}) {
  const hasAny =
    timeline.recent.length > 0 ||
    timeline.weeklySummaries.length > 0 ||
    timeline.archived.length > 0;

  if (!hasAny) {
    return (
      <div className="room-empty-note">
        <ChthollyIllustration size="sm" state="reading" />
        <p>今天的书桌还很安静。等她读到新的文章，想法会慢慢留在这里。</p>
      </div>
    );
  }

  return (
    <div className="room-experience-stack">
      {timeline.recent.length > 0 && (
        <ExperienceGroup
          title="最近 7 天"
          items={timeline.recent.map((experience) => ({
            key: `${experience.createdAt}-${experience.text}`,
            text: experience.text,
            meta: formatExperienceTime(experience.createdAt),
            dateTime: experience.createdAt,
          }))}
        />
      )}
      {timeline.weeklySummaries.length > 0 && (
        <ExperienceGroup
          title="7–30 天"
          items={timeline.weeklySummaries.map((summary) => ({
            key: summary.weekKey,
            text: summary.summary,
            meta: summary.weekKey,
          }))}
        />
      )}
      {timeline.archived.length > 0 && (
        <ExperienceGroup
          title="难忘时刻"
          items={timeline.archived.map((experience) => ({
            key: String(experience.id),
            text: experience.text,
            meta: `重要度 ${experience.importance}/10`,
            dateTime: experience.createdAt,
          }))}
        />
      )}
    </div>
  );
}

function ExperienceGroup({
  title,
  items,
}: {
  title: string;
  items: Array<{ key: string; text: string; meta: string; dateTime?: string }>;
}) {
  return (
    <section className="room-experience-group">
      <h3>{title}</h3>
      <ul className="room-experience-list">
        {items.map((item) => (
          <li key={item.key}>
            <p>{item.text}</p>
            {item.dateTime ? (
              <time dateTime={item.dateTime}>{item.meta}</time>
            ) : (
              <span>{item.meta}</span>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function formatExperienceTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "刚刚";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}
