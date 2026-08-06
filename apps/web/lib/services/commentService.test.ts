import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ apiFetch: vi.fn() }));

vi.mock("./apiClient", () => ({ apiFetch: mocks.apiFetch }));

import { commentService } from "./commentService";

describe("commentService", () => {
  beforeEach(() => {
    mocks.apiFetch.mockReset();
    mocks.apiFetch.mockResolvedValue({
      items: [],
      total: 0,
      page: 1,
      size: 20,
      hasMore: false,
    });
  });

  it("loads a requested page of user comment activity", async () => {
    await commentService.listByUser("9007199254740993", 2, 20);

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/api/v1/users/9007199254740993/comments?page=2&size=20",
    );
  });

  it("encodes the user ID as a path segment", async () => {
    await commentService.listByUser("user/ name?", 1, 20);

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/api/v1/users/user%2F%20name%3F/comments?page=1&size=20",
    );
  });
});
