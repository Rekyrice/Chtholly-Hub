import Image from "next/image";
import { notFound } from "next/navigation";
import { CalendarDays, FileText } from "lucide-react";
import Sidebar from "@/components/site/Sidebar";
import UserRelationPanel from "@/components/site/UserRelationPanel";
import ChthollyImpression from "@/components/site/ChthollyImpression";
import UserTabs from "@/components/site/UserTabs";
import { postService } from "@/lib/services/postService";
import { relationService } from "@/lib/services/relationService";
import { userService } from "@/lib/services/userService";
import type { UserCounter } from "@/lib/types/relation";

interface Props {
  params: Promise<{ handle: string }>;
}

export const revalidate = 60;

export async function generateMetadata({ params }: Props) {
  const { handle } = await params;
  try {
    const user = await userService.getByHandle(handle);
    return { title: `${user.nickname} (@${user.handle})` };
  } catch {
    return { title: "用户" };
  }
}

export default async function UserPage({ params }: Props) {
  const { handle } = await params;
  const handleNorm = decodeURIComponent(handle).trim().toLowerCase();

  let user: Awaited<ReturnType<typeof userService.getByHandle>>;
  try {
    user = await userService.getByHandle(handleNorm);
  } catch {
    notFound();
  }

  let items: Awaited<ReturnType<typeof postService.feed>>["items"] = [];
  try {
    const feed = await postService.feed(1, 50, user.id);
    items = feed.items;
  } catch {
    items = [];
  }

  let counter: UserCounter | undefined;
  try {
    counter = await relationService.counter(user.id);
  } catch {
    counter = {
      followings: 0,
      followers: 0,
      posts: user.publicPostCount,
      likedPosts: 0,
      favedPosts: 0,
    };
  }

  const displayName = user.nickname || user.handle;
  const initial = displayName.charAt(0) || "?";
  const recentTopic = inferRecentTopic(items);

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div>
        <section className="member-hero">
          <div className="member-hero__avatar">
            {user.avatar ? (
              <Image
                src={user.avatar}
                alt={displayName}
                width={132}
                height={132}
                priority
              />
            ) : (
              <span>{initial}</span>
            )}
          </div>

          <div className="member-hero__body">
            <div className="member-hero__identity">
              <div>
                <h1>{displayName}</h1>
                <p>@{user.handle}</p>
              </div>
              <UserRelationPanel userId={user.id} initialCounter={counter} />
            </div>

            {user.bio ? (
              <p className="member-hero__bio">{user.bio}</p>
            ) : (
              <p className="member-hero__bio member-hero__bio--empty">
                这个人还没有写简介。也许只是还没想好怎么介绍自己。
              </p>
            )}

            <div className="member-hero__meta">
              <span>
                <CalendarDays size={15} />
                {formatJoinedAt(user.createdAt)}
              </span>
              <span>
                <FileText size={15} />
                {counter?.posts ?? user.publicPostCount} 篇公开文章
              </span>
            </div>

            <ChthollyImpression
              nickname={displayName}
              counter={counter}
              recentTopic={recentTopic}
            />
          </div>
        </section>

        <UserTabs
          posts={items}
          displayName={displayName}
          userId={user.id}
          userHandle={user.handle}
        />
      </div>
      <Sidebar />
    </div>
  );
}

function formatJoinedAt(createdAt?: string | null) {
  if (!createdAt) return "加入时间还没公开";
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return "加入时间还没公开";
  return `加入于 ${date.getFullYear()}年${date.getMonth() + 1}月`;
}

function inferRecentTopic(items: Awaited<ReturnType<typeof postService.feed>>["items"]) {
  const first = items[0];
  if (!first) return undefined;
  return first.tags?.[0] || first.title;
}
