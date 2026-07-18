"use client";

import { useEffect, useRef, useState } from "react";
import SearchResultCard from "@/components/site/SearchResultCard";
import { searchService } from "@/lib/services/searchService";
import type { FeedItem } from "@/lib/types/post";
import type { SearchResponse, SearchSort } from "@/lib/types/search";

type SearchResultsProps = {
  query: string;
  tags: string[];
  sort: SearchSort;
  initial: SearchResponse;
};

type ResultsState = {
  scopeKey: string;
  items: FeedItem[];
  nextAfter: string | null;
  hasMore: boolean;
  loading: boolean;
  failed: boolean;
};

function scopeKey({ query, tags, sort, initial }: SearchResultsProps) {
  return JSON.stringify({ query, tags, sort, initial });
}

function initialState(key: string, initial: SearchResponse): ResultsState {
  return {
    scopeKey: key,
    items: initial.items,
    nextAfter: initial.nextAfter,
    hasMore: initial.hasMore,
    loading: false,
    failed: false,
  };
}

function appendUnique(current: FeedItem[], incoming: FeedItem[]) {
  const ids = new Set(current.map((item) => item.id));
  const additions: FeedItem[] = [];
  for (const item of incoming) {
    if (ids.has(item.id)) continue;
    ids.add(item.id);
    additions.push(item);
  }
  return [...current, ...additions];
}

export default function SearchResults(props: SearchResultsProps) {
  const currentScope = scopeKey(props);

  return <ScopedSearchResults key={currentScope} {...props} currentScope={currentScope} />;
}

type ScopedSearchResultsProps = SearchResultsProps & {
  currentScope: string;
};

function ScopedSearchResults({
  query,
  tags,
  sort,
  initial,
  currentScope,
}: ScopedSearchResultsProps) {
  const [state, setState] = useState(() => initialState(currentScope, initial));
  const mounted = useRef(false);
  const requestSequence = useRef(0);
  const loadingLock = useRef({ scopeKey: "", active: false });

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      requestSequence.current += 1;
    };
  }, []);

  const loadMore = async () => {
    if (!state.hasMore || !state.nextAfter) return;
    if (loadingLock.current.active && loadingLock.current.scopeKey === currentScope) return;

    const requestScope = currentScope;
    const sequence = ++requestSequence.current;
    const after = state.nextAfter;
    loadingLock.current = { scopeKey: requestScope, active: true };
    setState({ ...state, loading: true, failed: false });

    try {
      const response = await searchService.search({
        q: query,
        size: 12,
        tags,
        sort,
        after,
      });
      if (response.degraded) throw new Error("search degraded");
      if (!mounted.current || sequence !== requestSequence.current) return;

      setState((current) => {
        if (current.scopeKey !== requestScope) return current;
        return {
          scopeKey: requestScope,
          items: appendUnique(current.items, response.items),
          nextAfter: response.nextAfter,
          hasMore: response.hasMore,
          loading: false,
          failed: false,
        };
      });
    } catch {
      if (!mounted.current || sequence !== requestSequence.current) return;
      setState((current) =>
        current.scopeKey === requestScope
          ? { ...current, loading: false, failed: true }
          : current,
      );
    } finally {
      if (loadingLock.current.scopeKey === requestScope) {
        loadingLock.current = { scopeKey: requestScope, active: false };
      }
    }
  };

  const liveMessage = state.loading
    ? "正在加载更多结果…"
    : state.failed
      ? "加载失败，请重试"
      : state.hasMore
        ? `已显示 ${state.items.length} 条结果`
        : "已加载全部结果";

  return (
    <section
      className="search-results"
      aria-label="搜索结果"
      aria-busy={state.loading}
      role="region"
    >
      <div className="search-results__list">
        {state.items.map((post) => (
          <SearchResultCard key={post.id} post={post} query={query} />
        ))}
      </div>

      <p className="search-results__status" role="status" aria-live="polite">
        {liveMessage}
      </p>

      {state.hasMore && state.nextAfter && (
        <button
          type="button"
          className="search-results__more"
          disabled={state.loading}
          onClick={() => void loadMore()}
        >
          {state.loading ? "加载中…" : state.failed ? "重试" : "加载更多"}
        </button>
      )}
    </section>
  );
}
