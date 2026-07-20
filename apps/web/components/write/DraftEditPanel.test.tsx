import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import DraftEditPanel from "./DraftEditPanel";

const mocks = vi.hoisted(() => ({
  createPreview: vi.fn(),
  confirmPreview: vi.fn(),
  rejectPreview: vi.fn(),
  sha256Hex: vi.fn(),
}));

vi.mock("@/lib/utils/sha256", () => ({ sha256Hex: mocks.sha256Hex }));

vi.mock("@/lib/services/postService", () => ({
  postService: {
    createDraftEditPreview: mocks.createPreview,
    confirmDraftEditPreview: mocks.confirmPreview,
    rejectDraftEditPreview: mocks.rejectPreview,
  },
}));

const baseSha = "a".repeat(64);
const candidateSha = "b".repeat(64);
const previewHash = "c".repeat(64);

function preview() {
  return {
    previewId: "99",
    draftId: "42",
    skillId: "draft-edit",
    skillVersion: "v1",
    baseContentSha256: baseSha,
    candidateContentSha256: candidateSha,
    previewHash,
    candidateContent: "# 修改后的正文",
    status: "PENDING" as const,
    expiresAt: "2026-07-20T00:00:00Z",
  };
}

describe("DraftEditPanel", () => {
  beforeEach(() => {
    mocks.sha256Hex.mockImplementation(async (text: string) =>
      text === "# 原文" ? baseSha : "d".repeat(64),
    );
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("synchronizes the exact editor version before requesting a pinned preview", async () => {
    const ensureDraftContent = vi.fn(async () => ({ draftId: "42", sha256: baseSha }));
    mocks.createPreview.mockResolvedValue(preview());
    render(
      <DraftEditPanel
        markdown="# 原文"
        ensureDraftContent={ensureDraftContent}
        onApply={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText("编辑要求"), { target: { value: "润色语句" } });
    fireEvent.click(screen.getByRole("button", { name: "生成受控预览" }));

    await waitFor(() => expect(ensureDraftContent).toHaveBeenCalledWith("# 原文"));
    expect(mocks.createPreview).toHaveBeenCalledWith("42", {
      baseContent: "# 原文",
      baseContentSha256: baseSha,
      instruction: "润色语句",
    });
    expect(await screen.findByText("# 修改后的正文")).toBeInTheDocument();
    expect(screen.getByText("draft-edit@v1")).toBeInTheDocument();
  });

  it("blocks confirmation when the editor changed after preview creation", async () => {
    const ensureDraftContent = vi.fn(async () => ({ draftId: "42", sha256: baseSha }));
    mocks.createPreview.mockResolvedValue(preview());
    const { rerender } = render(
      <DraftEditPanel
        markdown="# 原文"
        ensureDraftContent={ensureDraftContent}
        onApply={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByLabelText("编辑要求"), { target: { value: "润色" } });
    fireEvent.click(screen.getByRole("button", { name: "生成受控预览" }));
    await screen.findByText("# 修改后的正文");

    rerender(
      <DraftEditPanel
        markdown="# 用户又改了"
        ensureDraftContent={ensureDraftContent}
        onApply={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "确认写入草稿" }));

    expect(await screen.findByText("正文已变化，请重新生成预览。")).toBeInTheDocument();
    expect(mocks.confirmPreview).not.toHaveBeenCalled();
  });

  it("applies only the confirmed server candidate and supports explicit rejection", async () => {
    const ensureDraftContent = vi.fn(async () => ({ draftId: "42", sha256: baseSha }));
    const onApply = vi.fn();
    mocks.createPreview.mockResolvedValue(preview());
    mocks.confirmPreview.mockResolvedValue({
      previewId: "99",
      draftId: "42",
      status: "APPLIED",
      contentSha256: candidateSha,
      contentUrl: "/candidate.md",
    });
    mocks.rejectPreview.mockResolvedValue({
      previewId: "99",
      draftId: "42",
      status: "REJECTED",
      contentSha256: null,
      contentUrl: null,
    });
    const { unmount } = render(
      <DraftEditPanel
        markdown="# 原文"
        ensureDraftContent={ensureDraftContent}
        onApply={onApply}
      />,
    );
    fireEvent.change(screen.getByLabelText("编辑要求"), { target: { value: "润色" } });
    fireEvent.click(screen.getByRole("button", { name: "生成受控预览" }));
    await screen.findByText("# 修改后的正文");
    fireEvent.click(screen.getByRole("button", { name: "确认写入草稿" }));

    await waitFor(() => expect(mocks.confirmPreview).toHaveBeenCalledWith("42", "99", previewHash));
    expect(onApply).toHaveBeenCalledWith("# 修改后的正文");
    expect(await screen.findByText("候选已写入草稿。")).toBeInTheDocument();
    unmount();

    render(
      <DraftEditPanel
        markdown="# 原文"
        ensureDraftContent={ensureDraftContent}
        onApply={onApply}
      />,
    );
    fireEvent.change(screen.getByLabelText("编辑要求"), { target: { value: "润色" } });
    fireEvent.click(screen.getByRole("button", { name: "生成受控预览" }));
    await screen.findByText("# 修改后的正文");
    fireEvent.click(screen.getByRole("button", { name: "拒绝此候选" }));

    await waitFor(() => expect(mocks.rejectPreview).toHaveBeenCalledWith("42", "99", previewHash));
    expect(await screen.findByText("候选已拒绝，原草稿未改变。")).toBeInTheDocument();
  });

  it("does not apply a server response whose content hash differs from the preview", async () => {
    const ensureDraftContent = vi.fn(async () => ({ draftId: "42", sha256: baseSha }));
    const onApply = vi.fn();
    mocks.createPreview.mockResolvedValue(preview());
    mocks.confirmPreview.mockResolvedValue({
      previewId: "99",
      draftId: "42",
      status: "APPLIED",
      contentSha256: "d".repeat(64),
      contentUrl: "/wrong.md",
    });
    render(
      <DraftEditPanel markdown="# 原文" ensureDraftContent={ensureDraftContent} onApply={onApply} />,
    );
    fireEvent.change(screen.getByLabelText("编辑要求"), { target: { value: "润色" } });
    fireEvent.click(screen.getByRole("button", { name: "生成受控预览" }));
    await screen.findByText("# 修改后的正文");
    fireEvent.click(screen.getByRole("button", { name: "确认写入草稿" }));

    expect(await screen.findByText("服务端候选完整性校验失败，原文未替换。")).toBeInTheDocument();
    expect(onApply).not.toHaveBeenCalled();
  });
});
