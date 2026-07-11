import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import FollowListModal from "@/components/site/FollowListModal";
import { relationService } from "@/lib/services/relationService";
import type { PageResponse, ProfileResponse } from "@/lib/types/relation";

vi.mock("next/image", () => ({ default: () => null }));
vi.mock("@/components/site/FollowButton", () => ({ default: () => null }));
vi.mock("@/lib/services/relationService", () => ({
  relationService: {
    following: vi.fn(),
    followers: vi.fn(),
  },
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

function page(items: ProfileResponse[], hasMore = false): PageResponse<ProfileResponse> {
  return { items, hasMore, nextCursor: hasMore ? "next" : null };
}

const followingUser: ProfileResponse = { id: 1, handle: "following", nickname: "关注用户" };
const staleUser: ProfileResponse = { id: 2, handle: "stale", nickname: "过期分页用户" };
const followerUser: ProfileResponse = { id: 3, handle: "follower", nickname: "粉丝用户" };

describe("FollowListModal request races", () => {
  let intersectionCallback: IntersectionObserverCallback | null = null;

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    intersectionCallback = null;
  });

  it("ignores an old tab pagination response that resolves after the new tab", async () => {
    vi.stubGlobal(
      "IntersectionObserver",
      class {
        constructor(callback: IntersectionObserverCallback) {
          intersectionCallback = callback;
        }
        observe() {}
        disconnect() {}
        unobserve() {}
        takeRecords() { return []; }
        root = null;
        rootMargin = "0px";
        thresholds = [];
      },
    );

    const stalePage = deferred<PageResponse<ProfileResponse>>();
    const followersPage = deferred<PageResponse<ProfileResponse>>();
    vi.mocked(relationService.following)
      .mockResolvedValueOnce(page([followingUser], true))
      .mockReturnValueOnce(stalePage.promise);
    vi.mocked(relationService.followers).mockReturnValueOnce(followersPage.promise);

    render(<FollowListModal userId="owner" open initialTab="following" onClose={vi.fn()} />);
    expect(await screen.findByText("关注用户")).toBeInTheDocument();
    await waitFor(() => expect(intersectionCallback).not.toBeNull());

    await act(async () => {
      intersectionCallback?.([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
    });
    expect(relationService.following).toHaveBeenCalledTimes(2);

    fireEvent.click(screen.getByRole("tab", { name: "粉丝" }));
    await waitFor(() => expect(relationService.followers).toHaveBeenCalledTimes(1));
    followersPage.resolve(page([followerUser]));
    expect(await screen.findByText("粉丝用户")).toBeInTheDocument();

    stalePage.resolve(page([staleUser]));
    await act(async () => Promise.resolve());
    expect(screen.queryByText("过期分页用户")).not.toBeInTheDocument();
    expect(screen.getByText("粉丝用户")).toBeInTheDocument();
  });

  it("does not write back when an in-flight request resolves after unmount", async () => {
    vi.stubGlobal(
      "IntersectionObserver",
      class {
        constructor() {}
        observe() {}
        disconnect() {}
        unobserve() {}
        takeRecords() { return []; }
        root = null;
        rootMargin = "0px";
        thresholds = [];
      },
    );
    const request = deferred<PageResponse<ProfileResponse>>();
    vi.mocked(relationService.following).mockReturnValueOnce(request.promise);
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);

    const { unmount } = render(
      <FollowListModal userId="owner" open initialTab="following" onClose={vi.fn()} />,
    );
    await waitFor(() => expect(relationService.following).toHaveBeenCalledTimes(1));
    unmount();
    request.resolve(page([followingUser]));
    await act(async () => Promise.resolve());

    expect(consoleError).not.toHaveBeenCalled();
    consoleError.mockRestore();
  });
});
