import Image from "next/image";
import Link from "next/link";
import { Badge } from "@/components/ui/Badge";
import { ChthollyIllustration } from "@/components/site/ChthollyIllustration";
import { postService } from "@/lib/services/postService";
import { tagService } from "@/lib/services/tagService";
import { siteConfig } from "@/lib/site.config";
import type { FeedItem } from "@/lib/types/post";
import type { AgentExperienceItem } from "@/lib/types/search";
import type { TagItem } from "@/lib/types/tag";

type SidebarProps = {
  items?: FeedItem[];
  tags?: TagItem[];
  recommendations?: FeedItem[];
  hotPosts?: FeedItem[];
  experiences?: AgentExperienceItem[];
  latestStatus?: "ok" | "degraded";
  tagsStatus?: "ok" | "degraded";
  recommendationsStatus?: "ok" | "degraded";
  experiencesStatus?: "ok" | "degraded";
};

export default async function Sidebar({
  items: providedItems,
  tags: providedTags,
  recommendations = [],
  hotPosts = [],
  experiences = [],
  latestStatus,
  tagsStatus,
  recommendationsStatus,
  experiencesStatus,
}: SidebarProps = {}) {
  let items: FeedItem[] = providedItems ?? [];
  let tags: TagItem[] = providedTags ?? [];

  if (providedItems == null) {
    try {
      const feed = await postService.feed(1, 50, siteConfig.ownerUserId);
      items = feed.items;
    } catch {
      items = [];
    }
  }

  if (providedTags == null) {
    try {
      tags = await tagService.list(50);
    } catch {
      tags = [];
    }
  }

  const profileName = siteConfig.author.name;

  return (
    <aside className="sidebar-scroll-hidden sticky top-[52px] max-h-[calc(100vh-52px)] overflow-y-auto">
      <div className="widget text-center">
        <Link
          href={`/user/${siteConfig.ownerHandle}`}
          className="no-underline text-inherit hover:opacity-90 transition-opacity duration-150"
        >
          <div className="w-40 h-40 mx-auto rounded-full overflow-hidden shadow-md border-2 border-surface flex items-center justify-center">
            <span className="navbar-brand-icon navbar-brand-icon--lg" aria-hidden="true">
              C
            </span>
          </div>
          <div className="mt-3.5 text-lg text-text font-medium">{profileName}</div>
        </Link>
        <p className="mt-2 text-sm text-text-secondary">{siteConfig.author.bio}</p>
        <div className="mt-3 grid grid-cols-2 gap-1.5">
          <Link href="/hub" className="no-underline hover:opacity-80 transition-opacity duration-150">
            <div className="text-3xl leading-tight text-text">{items.length}</div>
            <div className="text-sm text-text-secondary">文章</div>
          </Link>
          <Link href="/archive" className="no-underline hover:opacity-80 transition-opacity duration-150">
            <div className="text-3xl leading-tight text-text">{tags.length}</div>
            <div className="text-sm text-text-secondary">标签</div>
          </Link>
        </div>
      </div>

      <SidebarObservation experiences={experiences} degraded={experiencesStatus === "degraded"} />

      <SidebarHotPosts posts={hotPosts} />

      <SidebarPostList
        title="最新文章"
        posts={items.slice(0, 5)}
        degraded={latestStatus === "degraded"}
        degradedText="暂时无法获取，稍后再试试。"
      />

      <SidebarPostList
        title="推荐内容"
        posts={recommendations.slice(0, 5)}
        degraded={recommendationsStatus === "degraded"}
        degradedText="推荐暂时走丢了，等一下就好。"
      />

      <ActiveUsers items={items} />

      {tagsStatus === "degraded" ? (
        <div className="widget">
          <h3 className="widget-title">热门标签</h3>
          <p className="text-sm text-text-secondary m-0">标签热度暂时没有回来。</p>
        </div>
      ) : tags.length > 0 ? (
        <div className="widget">
          <h3 className="widget-title">热门标签</h3>
          <div className="hot-tags-cloud">
            {tags.slice(0, 15).map((tag) => (
              <Link
                key={tag.id}
                href={`/tag/${encodeURIComponent(tag.name)}`}
                className="no-underline hover:opacity-80 transition-opacity duration-150"
                title={`${tag.usageCount} 篇`}
              >
                <Badge className="bg-sky text-on-primary hover:bg-sky-deep">{tag.name}</Badge>
              </Link>
            ))}
          </div>
        </div>
      ) : null}
    </aside>
  );
}

function SidebarObservation({
  experiences,
  degraded,
}: {
  experiences: AgentExperienceItem[];
  degraded: boolean;
}) {
  const lines = experiences
    .map((experience) => experience.text)
    .filter(Boolean)
    .slice(0, 2);

  return (
    <div className="widget sidebar-observation-widget">
      <div className="sidebar-observation-widget__head">
        <ChthollyIllustration size="xs" mood={0} pageContext="/hub" />
        <div>
          <span>Observation</span>
          <h3>珂朵莉观察</h3>
        </div>
      </div>
      {degraded ? (
        <p>她现在有点安静，稍后再听听看。</p>
      ) : lines.length > 0 ? (
        lines.map((line, index) => <p key={`${line}-${index}`}>{line}</p>)
      ) : (
        <p>今天仓库里还算安静。没有关系，安静的时候也适合读一点东西。</p>
      )}
    </div>
  );
}

function SidebarHotPosts({ posts }: { posts: FeedItem[] }) {
  const hotPosts = posts
    .slice()
    .sort((a, b) => {
      const scoreA = (a.likeCount ?? 0) * 3 + (a.commentCount ?? 0) * 2 + (a.favoriteCount ?? 0);
      const scoreB = (b.likeCount ?? 0) * 3 + (b.commentCount ?? 0) * 2 + (b.favoriteCount ?? 0);
      return scoreB - scoreA;
    })
    .slice(0, 6);

  if (hotPosts.length === 0) return null;

  return (
    <div className="widget sidebar-hot-posts">
      <h3 className="widget-title">热门文章</h3>
      <ol className="sidebar-hot-posts__list">
        {hotPosts.map((post, index) => (
          <li key={post.id}>
            <Link href={`/post/${post.slug}`}>
              <span>{index + 1}</span>
              <strong>{post.title}</strong>
              <small>{post.likeCount ?? 0} 赞 · {post.commentCount ?? 0} 评论</small>
            </Link>
          </li>
        ))}
      </ol>
    </div>
  );
}

function SidebarPostList({
  title,
  posts,
  degraded,
  degradedText,
}: {
  title: string;
  posts: FeedItem[];
  degraded: boolean;
  degradedText: string;
}) {
  if (degraded) {
    return (
      <div className="widget">
        <h3 className="widget-title">{title}</h3>
        <p className="text-sm text-text-secondary m-0">{degradedText}</p>
      </div>
    );
  }

  if (posts.length === 0) return null;

  return (
    <div className="widget">
      <h3 className="widget-title">{title}</h3>
      <ul className="list-none p-0 m-0">
        {posts.map((post) => (
          <li key={post.id} className="border-b border-border py-2 text-sm last:border-b-0">
            <Link
              href={`/post/${post.slug}`}
              className="text-text no-underline block hover:text-sky transition-colors duration-150"
            >
              {post.title}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ActiveUsers({ items }: { items: FeedItem[] }) {
  const users = deriveActiveUsers(items);
  if (users.length === 0) return null;

  return (
    <div className="widget">
      <h3 className="widget-title">活跃用户</h3>
      <div className="active-users">
        {users.map((user) => (
          <Link
            key={user.id}
            href={user.handle ? `/user/${encodeURIComponent(user.handle)}` : "/hub"}
            className="active-users__item"
          >
            {user.avatar ? (
              <Image src={user.avatar} alt="" width={34} height={34} />
            ) : (
              <span>{user.name.charAt(0)}</span>
            )}
            <span className="active-users__body">
              <strong>{user.name}</strong>
              <small>{user.count} 篇最近文章</small>
            </span>
          </Link>
        ))}
      </div>
    </div>
  );
}

function deriveActiveUsers(items: FeedItem[]) {
  const users = new Map<string, {
    id: string;
    handle?: string;
    avatar?: string;
    name: string;
    count: number;
  }>();

  items.forEach((post) => {
    const id = post.authorId ?? post.authorHandle ?? post.authorNickname;
    if (!id) return;
    const current = users.get(String(id));
    if (current) {
      current.count += 1;
      return;
    }
    users.set(String(id), {
      id: String(id),
      handle: post.authorHandle,
      avatar: post.authorAvatar,
      name: post.authorNickname || post.authorHandle || "仓库居民",
      count: 1,
    });
  });

  return [...users.values()]
    .sort((a, b) => b.count - a.count)
    .slice(0, 8);
}
