import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import WritePage from "@/app/(site)/write/page";

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  refresh: vi.fn(),
  suggestDescription: vi.fn(async () => ({ description: "AI 摘要" })),
  createDraft: vi.fn(async () => ({ id: "draft-1" })),
  confirmContent: vi.fn(async () => undefined),
  createDraftEditPreview: vi.fn(async () => ({
    previewId: "preview-1",
    draftId: "draft-1",
    skillId: "draft-edit",
    skillVersion: "v1",
    baseContentSha256: "a".repeat(64),
    candidateContentSha256: "b".repeat(64),
    previewHash: "c".repeat(64),
    candidateContent: "正文（已润色）",
    status: "PENDING",
    expiresAt: "2026-07-20T00:00:00Z",
  })),
  presign: vi.fn(async () => ({
    objectKey: "draft-1/image.png",
    putUrl: "https://example.com/image.png?signature=test",
    publicUrl: "https://cdn.example.com/image.png",
  })),
  uploadPut: vi.fn(async () => "etag"),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mocks.push, refresh: mocks.refresh }),
}));

vi.mock("@/lib/hooks/useRequireAuth", () => ({ useRequireAuth: () => true }));
vi.mock("@/lib/services/postAiService", () => ({
  postAiService: { suggestDescription: mocks.suggestDescription },
}));
vi.mock("@/lib/services/postService", () => ({
  postService: {
    createDraft: mocks.createDraft,
    confirmContent: mocks.confirmContent,
    createDraftEditPreview: mocks.createDraftEditPreview,
    confirmDraftEditPreview: vi.fn(),
    rejectDraftEditPreview: vi.fn(),
    patchMetadata: vi.fn(),
    publish: vi.fn(),
  },
}));
vi.mock("@/lib/services/storageService", () => ({
  storageService: { presign: mocks.presign, uploadPut: mocks.uploadPut },
}));
vi.mock("@/lib/utils/sha256", () => ({
  sha256Hex: vi.fn(async () => "a".repeat(64)),
}));
vi.mock("@/components/write/TagAutocomplete", () => ({
  default: ({ onChange }: { onChange: (tags: string[]) => void }) => (
    <button type="button" onClick={() => onChange(["动画"])}>
      修改标签
    </button>
  ),
}));
vi.mock("@/components/write/MarkdownToolbar", () => ({
  default: ({
    value,
    onChange,
    onImageUpload,
  }: {
    value: string;
    onChange: (value: string) => void;
    onImageUpload: (file: File) => Promise<void>;
  }) => (
    <>
      <button type="button" onClick={() => onChange(`${value}正文`)}>
        修改正文
      </button>
      <button
        type="button"
        onClick={() => void onImageUpload(new File(["image"], "cover.png", { type: "image/png" }))}
      >
        插入图片
      </button>
    </>
  ),
}));
async function finishAutosave() {
  await act(async () => vi.advanceTimersByTimeAsync(700));
  expect(screen.getByText("保存中...")).toBeInTheDocument();
  await act(async () => vi.advanceTimersByTimeAsync(180));
  expect(screen.getByText("已保存")).toBeInTheDocument();
}

describe("WritePage draft status", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it("keeps the complete writing workspace while visual surfaces change", () => {
    render(<WritePage />);

    expect(screen.getByTestId("write-workspace-layout")).toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "写作辅助" })).toBeInTheDocument();

    expect(screen.getByRole("textbox", { name: "标题" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "摘要" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "编辑" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "预览" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "发布" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "AI 生成描述" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "修改标签" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "修改正文" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "插入图片" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "预览" }));
    expect(screen.getByText("还没有内容呢。")).toBeInTheDocument();
  });

  it("marks every user or programmatic draft edit dirty before autosaving", async () => {
    render(<WritePage />);
    expect(screen.getByText("已保存")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("标题"), { target: { value: "新标题" } });
    expect(screen.getByText("有未保存的更改")).toBeInTheDocument();
    await finishAutosave();

    fireEvent.change(screen.getByLabelText("摘要"), { target: { value: "新摘要" } });
    expect(screen.getByText("有未保存的更改")).toBeInTheDocument();
    await finishAutosave();

    fireEvent.click(screen.getByText("修改正文"));
    expect(screen.getByText("有未保存的更改")).toBeInTheDocument();
    await finishAutosave();

    fireEvent.click(screen.getByText("修改标签"));
    expect(screen.getByText("有未保存的更改")).toBeInTheDocument();
    await finishAutosave();

    fireEvent.click(screen.getByText("AI 生成描述"));
    await act(async () => Promise.resolve());
    expect(screen.getByText("有未保存的更改")).toBeInTheDocument();
    await finishAutosave();

    fireEvent.click(screen.getByText("插入图片"));
    await act(async () => Promise.resolve());
    expect(screen.getByText("有未保存的更改")).toBeInTheDocument();
  });

  it("uploads and confirms the exact markdown before requesting a controlled preview", async () => {
    render(<WritePage />);
    fireEvent.click(screen.getByText("修改正文"));
    fireEvent.change(screen.getByLabelText("编辑要求"), { target: { value: "润色" } });
    fireEvent.click(screen.getByRole("button", { name: "生成受控预览" }));

    await act(async () => {
      for (let index = 0; index < 12; index += 1) await Promise.resolve();
    });

    expect(mocks.confirmContent).toHaveBeenCalledWith("draft-1", {
      objectKey: "draft-1/image.png",
      etag: "etag",
      sha256: "a".repeat(64),
      size: new TextEncoder().encode("正文").length,
    });
    expect(mocks.createDraftEditPreview).toHaveBeenCalledWith("draft-1", {
      baseContent: "正文",
      baseContentSha256: "a".repeat(64),
      instruction: "润色",
    });
    expect(mocks.createDraft.mock.invocationCallOrder[0])
      .toBeLessThan(mocks.presign.mock.invocationCallOrder[0]);
    expect(mocks.presign.mock.invocationCallOrder[0])
      .toBeLessThan(mocks.uploadPut.mock.invocationCallOrder[0]);
    expect(mocks.uploadPut.mock.invocationCallOrder[0])
      .toBeLessThan(mocks.confirmContent.mock.invocationCallOrder[0]);
    expect(mocks.confirmContent.mock.invocationCallOrder[0])
      .toBeLessThan(mocks.createDraftEditPreview.mock.invocationCallOrder[0]);
  });

  it("does not let an older save completion mark a newer draft saved", async () => {
    render(<WritePage />);

    fireEvent.change(screen.getByLabelText("标题"), { target: { value: "第一版" } });
    await act(async () => vi.advanceTimersByTimeAsync(700));
    expect(screen.getByText("保存中...")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("标题"), { target: { value: "第二版" } });
    expect(screen.getByText("有未保存的更改")).toBeInTheDocument();
    await act(async () => vi.advanceTimersByTimeAsync(180));
    expect(screen.getByText("有未保存的更改")).toBeInTheDocument();

    await act(async () => vi.advanceTimersByTimeAsync(520));
    expect(screen.getByText("保存中...")).toBeInTheDocument();
    await act(async () => vi.advanceTimersByTimeAsync(180));
    expect(screen.getByText("已保存")).toBeInTheDocument();
  });
});
