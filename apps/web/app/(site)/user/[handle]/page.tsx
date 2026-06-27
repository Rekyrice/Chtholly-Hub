import Sidebar from "@/components/site/Sidebar";
import PostCard from "@/components/site/PostCard";
import { postService } from "@/lib/services/postService";
import { userService } from "@/lib/services/userService";
import { siteConfig } from "@/lib/site.config";
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
          <div
            style={{
              width: 120,
              height: 120,
              margin: "0 auto",
              borderRadius: "50%",
              overflow: "hidden",
              boxShadow: "0 2px 14px rgba(0,0,0,0.08)",
              border: "2px solid rgba(255,255,255,0.75)",
              background: "#e0f2f1",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: siteConfig.theme.primary,
              fontSize: 40,
              fontWeight: 700,
            }}
          >
            {user.avatar ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={user.avatar}
                alt={displayName}
                style={{ width: "100%", height: "100%", objectFit: "cover" }}
              />
            ) : (
              initial
            )}
          </div>
          <h1
            className="mt-4 text-xl font-medium"
            style={{ color: "#424242" }}
          >
            {displayName}
          </h1>
          <p className="mt-1 text-sm" style={{ color: "#9e9e9e" }}>
            @{user.handle}
          </p>
          {user.bio && (
            <p className="mt-3 text-sm leading-relaxed" style={{ color: "#757575" }}>
              {user.bio}
            </p>
          )}
          <p className="mt-4 text-sm" style={{ color: "#757575" }}>
            {user.publicPostCount} 篇公开文章
          </p>
        </div>

        {items.length > 0 ? (
          items.map((post) => <PostCard key={post.id} post={post} />)
        ) : (
          <div className="post-card p-10 text-center" style={{ color: "#9e9e9e" }}>
            暂无公开文章
          </div>
        )}
      </div>
      <Sidebar />
    </div>
  );
}
