import { postService } from "@/lib/services/postService";
import type { PostDetailResponse, RelatedPostSummary } from "@/lib/types/post";

export type RelatedPostCardModel = {
  id: string;
  slug?: string;
  title: string;
  summary?: string;
  description?: string;
  coverImage?: string;
  authorHandle?: string;
  authorNickname?: string;
  authorAvatar?: string;
  sharedEntities?: string[];
  href?: string;
};

export async function loadRelatedPostCards(
  postId: string | number,
  limit = 3,
): Promise<RelatedPostCardModel[]> {
  let related: RelatedPostSummary[];
  try {
    related = await postService.related(postId);
  } catch {
    return [];
  }

  return Promise.all(
    related.slice(0, Math.max(0, Math.trunc(limit))).map(async (summary) => {
      try {
        const detail = await postService.detailById(summary.id);
        return mergeRelatedPostDetail(summary, detail);
      } catch {
        return {
          ...summary,
          href: summary.slug ? `/post/${summary.slug}` : undefined,
        };
      }
    }),
  );
}

function mergeRelatedPostDetail(
  summary: RelatedPostSummary,
  detail: PostDetailResponse,
): RelatedPostCardModel {
  return {
    ...summary,
    slug: detail.slug,
    title: summary.title || detail.title,
    description: summary.description || detail.description,
    coverImage: detail.images?.[0] || summary.coverImage,
    authorHandle: detail.authorHandle,
    authorNickname: detail.authorNickname || summary.authorNickname,
    authorAvatar: detail.authorAvatar,
    href: detail.slug ? `/post/${detail.slug}` : undefined,
  };
}
