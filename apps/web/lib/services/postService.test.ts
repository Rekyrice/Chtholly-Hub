import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ apiFetch: vi.fn() }));

vi.mock("./apiClient", () => ({ apiFetch: mocks.apiFetch }));

import { postService } from "./postService";

describe("postService draft edit", () => {
  beforeEach(() => mocks.apiFetch.mockReset());

  it("uses the dedicated preview, confirm and reject endpoints", async () => {
    mocks.apiFetch.mockResolvedValue({});
    const createBody = {
      baseContent: "# base",
      baseContentSha256: "a".repeat(64),
      instruction: "润色",
    };

    await postService.createDraftEditPreview("42", createBody);
    await postService.confirmDraftEditPreview("42", "99", "b".repeat(64));
    await postService.rejectDraftEditPreview("42", "99", "b".repeat(64));

    expect(mocks.apiFetch).toHaveBeenNthCalledWith(
      1,
      "/api/v1/posts/42/draft-edit/previews",
      { method: "POST", body: createBody },
    );
    expect(mocks.apiFetch).toHaveBeenNthCalledWith(
      2,
      "/api/v1/posts/42/draft-edit/previews/99/confirm",
      { method: "POST", body: { previewHash: "b".repeat(64) } },
    );
    expect(mocks.apiFetch).toHaveBeenNthCalledWith(
      3,
      "/api/v1/posts/42/draft-edit/previews/99/reject",
      { method: "POST", body: { previewHash: "b".repeat(64) } },
    );
  });
});
