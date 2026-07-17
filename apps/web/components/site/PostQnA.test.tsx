import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import PostQnA from "@/components/site/PostQnA";
import type { PostQaStreamEvent } from "@/lib/services/postAiService";

const { qaStream } = vi.hoisted(() => ({ qaStream: vi.fn() }));

vi.mock("@/lib/services/postAiService", async () => {
  const actual = await vi.importActual<typeof import("@/lib/services/postAiService")>(
    "@/lib/services/postAiService",
  );
  return {
    ...actual,
    postAiService: {
      ...actual.postAiService,
      qaStream,
    },
  };
});

vi.mock("@/components/site/ChthollyIllustration", () => ({
  ChthollyIllustration: () => <div data-testid="chtholly" />,
}));

async function* completedAnswer(answer: string): AsyncGenerator<PostQaStreamEvent> {
  yield { type: "delta", data: answer };
  yield { type: "done" };
}

async function* interruptedAnswer(answer: string): AsyncGenerator<PostQaStreamEvent> {
  yield { type: "delta", data: answer };
  throw new Error("connection reset");
}

async function* completedThenClosedNoisily(answer: string): AsyncGenerator<PostQaStreamEvent> {
  yield { type: "delta", data: answer };
  yield { type: "done" };
  throw new Error("late transport error");
}

function ask(question: string) {
  fireEvent.change(screen.getByPlaceholderText(/想问些什么呢/u), {
    target: { value: question },
  });
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
}

describe("PostQnA", () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    qaStream.mockReset();
  });

  it("keeps a completed answer in the normal state without an interruption warning", async () => {
    qaStream.mockImplementation(() => completedAnswer("她接受了落选，但没有否定自己的愿望。"));
    render(<PostQnA postId="42" />);

    ask("核心观点是什么？");

    const answer = await screen.findByText("她接受了落选，但没有否定自己的愿望。");
    await waitFor(() => expect(screen.getByPlaceholderText(/想问些什么呢/u)).toBeEnabled());
    expect(answer).not.toHaveClass("post-qna-turn__answer--error");
    expect(screen.queryByText(/回答暂时中断/u)).not.toBeInTheDocument();
  });

  it("preserves a partial answer without turning the answer text red", async () => {
    qaStream.mockImplementation(() => interruptedAnswer("她先停了一下，然后才继续往前。"));
    render(<PostQnA postId="42" />);

    ask("后来呢？");

    const answer = await screen.findByText("她先停了一下，然后才继续往前。");
    expect(await screen.findByText("回答暂时中断了。已保留收到的内容，可以稍后再问一次。"))
      .toBeInTheDocument();
    expect(answer).not.toHaveClass("post-qna-turn__answer--error");
  });

  it("does not overwrite a completed turn when a late transport error arrives", async () => {
    qaStream.mockImplementation(() => completedThenClosedNoisily("已经完整回答。"));
    render(<PostQnA postId="42" />);

    ask("完成了吗？");

    await screen.findByText("已经完整回答。");
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(screen.queryByText(/回答暂时中断/u)).not.toBeInTheDocument();
  });

  it("sends completed local turns as history with the next question", async () => {
    qaStream
      .mockImplementationOnce(() => completedAnswer("第一问的回答"))
      .mockImplementationOnce(() => completedAnswer("第二问的回答"));
    render(<PostQnA postId="42" />);

    ask("第一问");
    await screen.findByText("第一问的回答");
    await waitFor(() => expect(screen.getByPlaceholderText(/想问些什么呢/u)).toBeEnabled());

    ask("第二问");
    await screen.findByText("第二问的回答");

    expect(qaStream).toHaveBeenNthCalledWith(
      2,
      "42",
      "第二问",
      [{ question: "第一问", answer: "第一问的回答" }],
      expect.any(AbortSignal),
    );
  });
});
