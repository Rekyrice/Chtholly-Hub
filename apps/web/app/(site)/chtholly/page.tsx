import Link from "next/link";
import "../../styles/agent.css";
import { ChthollyIllustration } from "@/components/site/ChthollyIllustration";
import { agentService } from "@/lib/services/agentService";
import { postService } from "@/lib/services/postService";
import { tagService } from "@/lib/services/tagService";
import type { ChthollyIllustrationProps } from "@/components/site/ChthollyIllustration";
import type { AgentExperienceTimeline } from "@/lib/types/agent";
import type { FeedItem } from "@/lib/types/post";

export const metadata = {
  title: "Chtholly",
  description: "珂朵莉住着的房间，能看到她最近的想法、推荐和社区里的动静。",
};

export const revalidate = 60;

export default async function ChthollyRoom() {
  const mood = 0;
  const timeOfDay = getCurrentTimePeriod();
  const [timeline, feedItems, tagCount] = await Promise.all([
    loadExperienceTimeline(),
    loadRecommendations(),
    loadTagCount(),
  ]);

  return (
    <div className="chtholly-room">
      <section className="room-zone room-zone--door">
        <ChthollyIllustration size="lg" mood={mood} timeOfDay={timeOfDay} />
        <p className="room-mood">{getMoodMessage(mood, timeOfDay)}</p>
        <Link href="/agent" className="room-chat-btn">
          和她聊天
        </Link>
      </section>

      <section className="room-zone room-zone--desk">
        <h2>她最近在想什么</h2>
        <ExperienceList timeline={timeline} />
      </section>

      <section className="room-zone room-zone--shelf">
        <h2>她觉得值得看的</h2>
        <RecommendationList items={feedItems.slice(0, 4)} />
      </section>

      <section className="room-zone room-zone--window">
        <h2>她看到的社区</h2>
        <CommunityStats postCount={feedItems.length} tagCount={tagCount} />
      </section>
    </div>
  );
}

async function loadExperienceTimeline(): Promise<AgentExperienceTimeline> {
  try {
    return await agentService.experienceTimeline();
  } catch {
    return { recent: [], weeklySummaries: [], archived: [] };
  }
}

async function loadRecommendations(): Promise<FeedItem[]> {
  try {
    const feed = await postService.feed(1, 12);
    return feed.items;
  } catch {
    return [];
  }
}

async function loadTagCount(): Promise<number> {
  try {
    const tags = await tagService.list(100);
    return tags.length;
  } catch {
    return 0;
  }
}

function ExperienceList({ timeline }: { timeline: AgentExperienceTimeline }) {
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
        <ExperienceGroup title="最近 7 天" items={timeline.recent.map((experience) => ({
          key: `${experience.createdAt}-${experience.text}`,
          text: experience.text,
          meta: formatExperienceTime(experience.createdAt),
          dateTime: experience.createdAt,
        }))} />
      )}
      {timeline.weeklySummaries.length > 0 && (
        <ExperienceGroup title="7-30 天" items={timeline.weeklySummaries.map((summary) => ({
          key: summary.weekKey,
          text: summary.summary,
          meta: summary.weekKey,
        }))} />
      )}
      {timeline.archived.length > 0 && (
        <ExperienceGroup title="难忘时刻" items={timeline.archived.map((experience) => ({
          key: String(experience.id),
          text: experience.text,
          meta: `重要度 ${experience.importance}/10`,
          dateTime: experience.createdAt,
        }))} />
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

function RecommendationList({ items }: { items: FeedItem[] }) {
  if (items.length === 0) {
    return <p className="room-muted">书架暂时空着。嗯，等有新文章再认真摆好。</p>;
  }

  return (
    <div className="room-recommendation-list">
      {items.map((item) => (
        <Link key={item.id} href={`/post/${item.slug}`} className="room-recommendation">
          <span>{item.title}</span>
          <small>{item.description || "她把这篇轻轻放在了书架上。"}</small>
        </Link>
      ))}
    </div>
  );
}

function CommunityStats({ postCount, tagCount }: { postCount: number; tagCount: number }) {
  const stats = [
    { label: "最近可读文章", value: postCount },
    { label: "留下的标签", value: tagCount },
    { label: "窗边状态", value: "安静" },
  ];

  return (
    <dl className="room-community-stats">
      {stats.map((stat) => (
        <div key={stat.label}>
          <dt>{stat.label}</dt>
          <dd>{stat.value}</dd>
        </div>
      ))}
    </dl>
  );
}

function getMoodMessage(
  mood: number,
  timeOfDay: NonNullable<ChthollyIllustrationProps["timeOfDay"]>,
) {
  if (timeOfDay === "late-night") return "夜已经很深了。她把灯留得很暗，像是在等一句轻声的晚安。";
  if (timeOfDay === "night") return "今晚仓库里很安静。要聊的话，她会坐在这里听你说。";
  if (mood > 0.3) return "她今天看起来轻松一点。嗯，还行吧，大概是这样。";
  if (mood < -0.3) return "她似乎有点发呆。先别急，陪她坐一会儿就好。";
  return "这里就像一个温暖的仓库。有人来，就有人被好好记住。";
}

function getCurrentTimePeriod(): NonNullable<ChthollyIllustrationProps["timeOfDay"]> {
  const hour = new Date().getHours();
  if (hour >= 6 && hour < 12) return "morning";
  if (hour >= 12 && hour < 18) return "afternoon";
  if (hour >= 18 && hour < 21) return "evening";
  if (hour >= 21 || hour < 1) return "night";
  return "late-night";
}

function formatExperienceTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "刚刚";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}
