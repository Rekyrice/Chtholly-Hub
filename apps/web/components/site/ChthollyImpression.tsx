import { Sparkles } from "lucide-react";
import type { UserCounter } from "@/lib/types/relation";

type ChthollyImpressionProps = {
  nickname: string;
  counter?: UserCounter;
  recentTopic?: string;
};

export default function ChthollyImpression({
  nickname,
  counter,
  recentTopic,
}: ChthollyImpressionProps) {
  const intimacy = estimateIntimacy(counter);
  const message = getImpressionMessage(intimacy, nickname, recentTopic);

  return (
    <section className="member-impression" aria-label="珂朵莉的印象">
      <div className="member-impression__icon" aria-hidden="true">
        <Sparkles size={18} />
      </div>
      <div>
        <p className="member-impression__label">珂朵莉的印象</p>
        <p className="member-impression__text">「{message}」</p>
      </div>
    </section>
  );
}

function estimateIntimacy(counter?: UserCounter) {
  if (!counter) return 0;
  const activity = Math.min(1, (counter.posts + counter.likedPosts + counter.favedPosts) / 24);
  const social = Math.min(1, (counter.followers + counter.followings) / 60);
  return Math.min(1, activity * 0.62 + social * 0.38);
}

function getImpressionMessage(intimacy: number, nickname: string, recentTopic?: string) {
  if (intimacy < 0.1) return "这个人好像刚来不久呢。先安静地记住名字吧。";
  if (intimacy < 0.3) return "见过几次面了，好像正在慢慢把自己的故事放进仓库。";
  if (intimacy < 0.6) {
    return recentTopic
      ? `经常来呢，上次好像在写关于${recentTopic}的话题。`
      : "经常来呢，留下的文章和足迹都变多了。";
  }
  if (intimacy < 0.9) return `${nickname}算是老朋友了。认真写东西的人，总会让人多看一眼。`;
  return "很亲近的人。我们聊过很多，所以有些话不用说得太满。";
}
