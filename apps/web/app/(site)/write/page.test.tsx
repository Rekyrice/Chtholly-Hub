import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import WritePage from "@/app/(site)/write/page";

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  refresh: vi.fn(),
  suggestDescription: vi.fn(async () => ({ description: "AI 摘要" })),
  createDraft: vi.fn(async () => ({ id: "draft-1" })),
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
    confirmContent: vi.fn(),
    patchMetadata: vi.fn(),
    publish: vi.fn(),
  },
}));
vi.mock("@/lib/services/storageService", () => ({
  storageService: { presign: mocks.presign, uploadPut: mocks.uploadPut },
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
vi.mock("@/components/write/WriteStats", () => ({ default: () => null }));

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
