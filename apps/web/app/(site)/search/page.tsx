import "../../styles/search.css";
import Link from "next/link";
import SearchAutocompleteForm from "@/components/site/SearchAutocompleteForm";
import SearchResults from "@/components/site/SearchResults";
import { postService } from "@/lib/services/postService";
import { searchService } from "@/lib/services/searchService";
import { tagService } from "@/lib/services/tagService";
import type { FeedItem } from "@/lib/types/post";
import type { SearchResponse, SearchSort } from "@/lib/types/search";
import type { TagItem } from "@/lib/types/tag";

type SearchParamValue = string | string[] | undefined;

interface Props {
  searchParams: Promise<{
    q?: SearchParamValue;
    tags?: SearchParamValue;
    sort?: SearchParamValue;
  }>;
}

export const revalidate = 60;

function firstParam(value: SearchParamValue) {
  return Array.isArray(value) ? value[0] ?? "" : value ?? "";
}

function parseTags(value: SearchParamValue) {
  return [...new Set(firstParam(value).split(",").map((tag) => tag.trim()).filter(Boolean))];
}

function parseSort(value: SearchParamValue): SearchSort {
  return firstParam(value) === "newest" ? "newest" : "relevance";
}

function emptySearchResponse(degraded = false): SearchResponse {
  return { items: [], nextAfter: null, hasMore: false, degraded };
}

function searchHref(q: string, tags: string[], sort: SearchSort) {
  const params = new URLSearchParams();
  const keyword = q.trim();
  const normalizedTags = [...new Set(tags.map((tag) => tag.trim()).filter(Boolean))];
  if (keyword) params.set("q", keyword);
  if (normalizedTags.length > 0) params.set("tags", normalizedTags.join(","));
  if (sort !== "relevance") params.set("sort", sort);
  const query = params.toString();
  return query ? `/search?${query}` : "/search";
}

function tagHref(tag: TagItem) {
  return `/tag/${encodeURIComponent(tag.slug || tag.name)}`;
}

export async function generateMetadata({ searchParams }: Props) {
  const params = await searchParams;
  const keyword = firstParam(params.q).trim();
  return { title: keyword ? `搜索：${keyword}` : "搜索" };
}

export default async function SearchPage({ searchParams }: Props) {
  const params = await searchParams;
  const keyword = firstParam(params.q).trim();
  const selectedTags = parseTags(params.tags);
  const sort = parseSort(params.sort);

  const tagsRequest = tagService.list(12).catch((): TagItem[] => []);
  const searchRequest = keyword
    ? searchService
        .search({ q: keyword, size: 12, tags: selectedTags, sort })
        .catch(() => emptySearchResponse(true))
    : Promise.resolve(emptySearchResponse());
  const recentRequest = keyword
    ? Promise.resolve([] as FeedItem[])
    : postService
        .feed(1, 4)
        .then((response) => response.items)
        .catch((): FeedItem[] => []);

  const [availableTags, searchResponse, recentPosts] = await Promise.all([
    tagsRequest,
    searchRequest,
    recentRequest,
  ]);
  const degraded = searchResponse.degraded === true;
  const selectedSet = new Set(selectedTags);
  const addableTags = availableTags.filter((tag) => !selectedSet.has(tag.name));
  const islandKey = `${keyword}\u0000${selectedTags.join(",")}\u0000${sort}`;

  return (
    <div className="search-page-layout">
      <main className="search-workbench">
        <header className="search-workbench__header">
          <div>
            <p className="search-workbench__eyebrow">EDITORIAL SEARCH</p>
            <h1>文章检索台</h1>
            <p>从标题、摘要与正文里找到下一条阅读线索。</p>
          </div>
          <SearchAutocompleteForm
            key={islandKey}
            initialQuery={keyword}
            tags={selectedTags}
            sort={sort}
          />
        </header>

        {keyword ? (
          <>
            <section className="search-filter-bar" aria-label="搜索筛选">
              <div className="search-filter-bar__summary">
                <span>关键词</span>
                <strong>{keyword}</strong>
              </div>

              {selectedTags.length > 0 && (
                <div className="search-filter-bar__selected" aria-label="已选标签">
                  {selectedTags.map((tag) => (
                    <Link
                      key={tag}
                      href={searchHref(
                        keyword,
                        selectedTags.filter((selected) => selected !== tag),
                        sort,
                      )}
                      aria-label={`移除标签 ${tag}`}
                    >
                      {tag}<span aria-hidden="true"> ×</span>
                    </Link>
                  ))}
                </div>
              )}

              <nav className="search-filter-bar__sort" aria-label="结果排序">
                <Link
                  href={searchHref(keyword, selectedTags, "relevance")}
                  aria-current={sort === "relevance" ? "page" : undefined}
                >
                  综合
                </Link>
                <Link
                  href={searchHref(keyword, selectedTags, "newest")}
                  aria-current={sort === "newest" ? "page" : undefined}
                >
                  最新
                </Link>
              </nav>

              {addableTags.length > 0 && (
                <div className="search-filter-bar__available">
                  <span>添加标签</span>
                  <div>
                    {addableTags.slice(0, 8).map((tag) => (
                      <Link
                        key={tag.id}
                        href={searchHref(keyword, [...selectedTags, tag.name], sort)}
                        aria-label={`添加标签 ${tag.name}`}
                      >
                        + {tag.name}
                      </Link>
                    ))}
                  </div>
                </div>
              )}
            </section>

            <div className="search-results-heading">
              <h2>检索结果</h2>
              {!degraded && <span>首屏 {searchResponse.items.length} 篇</span>}
            </div>

            {degraded && (
              <div className="search-degraded" role="alert">
                <strong>搜索服务暂时不可用</strong>
                <span>当前结果可能不完整，请稍后重试。</span>
              </div>
            )}

            {searchResponse.items.length > 0 ? (
              <SearchResults
                key={islandKey}
                query={keyword}
                tags={selectedTags}
                sort={sort}
                initial={searchResponse}
              />
            ) : !degraded ? (
              <section className="search-empty" aria-labelledby="search-empty-title">
                <span aria-hidden="true">00</span>
                <div>
                  <h2 id="search-empty-title">没有找到匹配的文章</h2>
                  <p>尝试缩短关键词、移除部分标签，或切换到“最新”排序。</p>
                </div>
              </section>
            ) : null}
          </>
        ) : (
          <SearchOverview tags={availableTags} recentPosts={recentPosts} />
        )}
      </main>

      <SearchSidebar
        keyword={keyword}
        selectedTags={selectedTags}
        sort={sort}
        tags={availableTags}
      />
    </div>
  );
}

function SearchOverview({ tags, recentPosts }: { tags: TagItem[]; recentPosts: FeedItem[] }) {
  return (
    <div className="search-overview">
      <section className="search-overview__routes" aria-labelledby="search-routes-title">
        <div>
          <p>先浏览，再精确检索</p>
          <h2 id="search-routes-title">从站点脉络开始</h2>
        </div>
        <nav aria-label="内容浏览入口">
          <Link href="/archive">浏览归档</Link>
          <Link href="/hub">前往 Hub</Link>
        </nav>
      </section>

      {tags.length > 0 && (
        <section className="search-overview__tags" aria-labelledby="search-tags-title">
          <div className="search-section-heading">
            <h2 id="search-tags-title">热门标签</h2>
            <span>{tags.length} 个入口</span>
          </div>
          <div>
            {tags.map((tag) => (
              <Link key={tag.id} href={tagHref(tag)}>
                <span>{tag.name}</span>
                <small>{tag.usageCount} 篇</small>
              </Link>
            ))}
          </div>
        </section>
      )}

      <section className="search-overview__recent" aria-labelledby="search-recent-title">
        <div className="search-section-heading">
          <h2 id="search-recent-title">最近发布</h2>
          <span>RECENT {String(recentPosts.slice(0, 4).length).padStart(2, "0")}</span>
        </div>
        {recentPosts.length > 0 ? (
          <ol>
            {recentPosts.slice(0, 4).map((post, index) => (
              <li key={post.id} data-testid="search-recent-item">
                <span>{String(index + 1).padStart(2, "0")}</span>
                <Link href={`/post/${encodeURIComponent(post.slug || "")}`}>
                  <strong>{post.title || "未命名文章"}</strong>
                  <small>{post.authorNickname || "匿名作者"}</small>
                </Link>
              </li>
            ))}
          </ol>
        ) : (
          <p className="search-overview__recent-empty">最近文章暂时没有加载出来。</p>
        )}
      </section>
    </div>
  );
}

function SearchSidebar({
  keyword,
  selectedTags,
  sort,
  tags,
}: {
  keyword: string;
  selectedTags: string[];
  sort: SearchSort;
  tags: TagItem[];
}) {
  return (
    <aside className="search-sidebar" aria-label="搜索辅助信息">
      <section className="search-sidebar__panel">
        <p className="search-sidebar__index">01 / GUIDE</p>
        <h2>检索说明</h2>
        <p>输入具体名词通常更容易命中；组合标签可以收窄结果，排序不会清除当前条件。</p>
      </section>

      {tags.length > 0 && (
        <section className="search-sidebar__panel">
          <p className="search-sidebar__index">02 / TAGS</p>
          <h2>热门标签</h2>
          <div className="search-sidebar__tags">
            {tags.slice(0, 8).map((tag) => (
              <Link
                key={tag.id}
                href={keyword
                  ? searchHref(keyword, [...selectedTags, tag.name], sort)
                  : tagHref(tag)}
                aria-label={keyword ? `筛选标签 ${tag.name}` : `浏览标签 ${tag.name}`}
              >
                {tag.name}<span>{tag.usageCount}</span>
              </Link>
            ))}
          </div>
        </section>
      )}

      <section className="search-sidebar__panel">
        <p className="search-sidebar__index">03 / ACTIVE</p>
        <h2>当前条件</h2>
        <dl className="search-sidebar__conditions">
          <div><dt>关键词</dt><dd>{keyword || "尚未输入"}</dd></div>
          <div><dt>标签</dt><dd>{selectedTags.join("、") || "全部"}</dd></div>
          <div><dt>排序</dt><dd>{sort === "newest" ? "最新" : "综合"}</dd></div>
        </dl>
      </section>
    </aside>
  );
}
