import "../../styles/agent.css";
import ChthollyExperienceTimeline from "@/components/site/ChthollyExperienceTimeline";
import ChthollyRecommendationShelf from "@/components/site/ChthollyRecommendationShelf";
import ChthollyRoomHero from "@/components/site/ChthollyRoomHero";
import ChthollyTopicWindow from "@/components/site/ChthollyTopicWindow";
import { agentService } from "@/lib/services/agentService";
import { postService } from "@/lib/services/postService";
import { tagService } from "@/lib/services/tagService";
import { topicService } from "@/lib/services/topicService";
import type { ChthollyIllustrationProps } from "@/components/site/ChthollyIllustration";
import type { AgentExperienceTimeline } from "@/lib/types/agent";
import type { FeedItem } from "@/lib/types/post";
import type { TagItem } from "@/lib/types/tag";
import type { TopicOverview } from "@/lib/types/topic";

export const metadata = {
  title: "Chtholly",
  description: "珂朵莉住着的房间，能看到她最近的想法、推荐和社区里的动静。",
};

export const revalidate = 60;

export default async function ChthollyRoom() {
  const mood = 0;
  const timeOfDay = getCurrentTimePeriod();
  const [timeline, feedItems, topicOverview, topicSignals] = await Promise.all([
    loadExperienceTimeline(),
    loadRecommendations(),
    loadTopicOverview(),
    loadTopicSignals(),
  ]);

  return (
    <div className="chtholly-room">
      <ChthollyRoomHero
        mood={mood}
        message={getMoodMessage(mood, timeOfDay)}
        timeOfDay={timeOfDay}
      />

      <div className="chtholly-room-sections">
        <section className="chtholly-room-panel chtholly-room-experience">
          <RoomPanelHeading eyebrow="MEMORY" title="今夜书桌" />
          <ChthollyExperienceTimeline timeline={timeline} />
        </section>

        <section className="chtholly-room-panel chtholly-room-topic">
          <RoomPanelHeading eyebrow="WINDOW NOTES" title="窗边便笺" />
          <ChthollyTopicWindow initialOverview={topicOverview} signals={topicSignals} />
        </section>

        <section className="chtholly-room-panel chtholly-room-recommendation">
          <RoomPanelHeading eyebrow="BOOKSHELF" title="她的书架" />
          <ChthollyRecommendationShelf items={feedItems} />
        </section>
      </div>
    </div>
  );
}

function RoomPanelHeading({ eyebrow, title }: { eyebrow: string; title: string }) {
  return (
    <header className="chtholly-room-panel__heading">
      <span>{eyebrow}</span>
      <h2>{title}</h2>
    </header>
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

async function loadTopicOverview(): Promise<TopicOverview> {
  try {
    return await topicService.overview();
  } catch {
    return {
      items: [],
      state: "FAILED",
      windowDays: 7,
      reason: "REQUEST_FAILED",
    };
  }
}

async function loadTopicSignals(): Promise<TagItem[]> {
  try {
    return await tagService.list(6);
  } catch {
    return [];
  }
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
