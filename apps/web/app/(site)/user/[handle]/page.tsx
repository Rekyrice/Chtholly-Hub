import Sidebar from "@/components/site/Sidebar";
import PostCard from "@/components/site/PostCard";
import { EmptyState } from "@/components/ui/EmptyState";
import { postService } from "@/lib/services/postService";
import { userService } from "@/lib/services/userService";
import { notFound } from "next/navigation";

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
    const feed = await postService.feed(1, 50, Number(user.id));
    items = feed.items;
  } catch {
    items = [];
  }

  const displayName = user.nickname || user.handle;
  const initial = displayName.charAt(0) || "?";

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div>
        <div className="post-card mb-6 p-8 text-center">
          <div className="w-[120px] h-[120px] mx-auto rounded-full overflow-hidden shadow-md border-2 border-surface avatar-ring flex items-center justify-center text-sky text-4xl font-bold">
            {user.avatar ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={user.avatar}
                alt={displayName}
                className="w-full h-full object-cover"
              />
            ) : (
              initial
            )}
          </div>
          <h1 className="mt-4 text-xl font-medium text-text">{displayName}</h1>
          <p className="mt-1 text-sm text-text-secondary">@{user.handle}</p>
          {user.bio && (
            <p className="mt-3 text-sm leading-relaxed text-text-secondary">{user.bio}</p>
          )}
          <p className="mt-4 text-sm text-text-secondary">{user.publicPostCount} 篇公开文章</p>
        </div>

        {items.length > 0 ? (
          items.map((post) => <PostCard key={post.id} post={post} />)
        ) : (
          <EmptyState className="post-card" title="暂无公开文章" />
        )}
      </div>
      <Sidebar />
    </div>
  );
}
