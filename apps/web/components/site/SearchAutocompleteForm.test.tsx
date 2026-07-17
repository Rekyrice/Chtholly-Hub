import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SearchAutocompleteForm from "@/components/site/SearchAutocompleteForm";
import { searchService } from "@/lib/services/searchService";

vi.mock("@/lib/services/searchService", () => ({
  searchService: {
    suggest: vi.fn(),
  },
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function advance(ms = 250) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe("SearchAutocompleteForm", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(searchService.suggest).mockReset();
    vi.mocked(searchService.suggest).mockResolvedValue({ items: [] });
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("renders a labelled GET combobox and preserves filter fields", () => {
    const { container } = render(
      <SearchAutocompleteForm
        initialQuery="雪原"
        tags={["随笔", "世界观"]}
        sort="newest"
      />,
    );

    const form = container.querySelector("form");
    expect(form).toHaveAttribute("method", "get");
    expect(form).toHaveAttribute("action", "/search");
    expect(screen.getByRole("combobox", { name: "搜索文章" })).toHaveValue("雪原");
    expect(container.querySelector('input[name="tags"]')).toHaveValue("随笔,世界观");
    expect(container.querySelector('input[name="sort"]')).toHaveValue("newest");
    expect(screen.getByRole("button", { name: "搜索" })).toBeVisible();
  });

  it("debounces suggestions for 250ms and clears an empty prefix", async () => {
    render(<SearchAutocompleteForm initialQuery="" tags={[]} sort="relevance" />);
    const input = screen.getByRole("combobox", { name: "搜索文章" });

    fireEvent.change(input, { target: { value: "珂朵莉" } });
    await advance(249);
    expect(searchService.suggest).not.toHaveBeenCalled();
    await advance(1);
    expect(searchService.suggest).toHaveBeenCalledWith("珂朵莉", 8);

    fireEvent.change(input, { target: { value: "" } });
    await advance();
    expect(searchService.suggest).toHaveBeenCalledTimes(1);
    expect(input).toHaveAttribute("aria-expanded", "false");
  });

  it("does not request during IME composition and requests once after composition ends", async () => {
    render(<SearchAutocompleteForm initialQuery="" tags={[]} sort="relevance" />);
    const input = screen.getByRole("combobox", { name: "搜索文章" });

    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: "珂" } });
    await advance(400);
    expect(searchService.suggest).not.toHaveBeenCalled();

    fireEvent.compositionEnd(input, { data: "珂" });
    await advance();
    expect(searchService.suggest).toHaveBeenCalledTimes(1);
    expect(searchService.suggest).toHaveBeenCalledWith("珂", 8);
  });

  it("does not let an older response overwrite suggestions for a newer query", async () => {
    const older = deferred<{ items: string[] }>();
    const newer = deferred<{ items: string[] }>();
    vi.mocked(searchService.suggest)
      .mockReturnValueOnce(older.promise)
      .mockReturnValueOnce(newer.promise);
    render(<SearchAutocompleteForm initialQuery="" tags={[]} sort="relevance" />);
    const input = screen.getByRole("combobox", { name: "搜索文章" });
    fireEvent.focus(input);

    fireEvent.change(input, { target: { value: "冬" } });
    await advance();
    fireEvent.change(input, { target: { value: "冬日" } });
    await advance();

    await act(async () => newer.resolve({ items: ["冬日手记"] }));
    expect(screen.getByRole("option", { name: "冬日手记" })).toBeVisible();
    await act(async () => older.resolve({ items: ["旧冬天"] }));
    expect(screen.queryByRole("option", { name: "旧冬天" })).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "冬日手记" })).toBeVisible();
  });

  it("cycles options and lets Enter select before a later submit", async () => {
    vi.mocked(searchService.suggest).mockResolvedValue({ items: ["一号", "二号"] });
    const { container } = render(
      <SearchAutocompleteForm initialQuery="" tags={[]} sort="relevance" />,
    );
    const input = screen.getByRole("combobox", { name: "搜索文章" });
    const form = container.querySelector("form")!;
    const submit = vi.fn((event: Event) => event.preventDefault());
    form.addEventListener("submit", submit);

    fireEvent.change(input, { target: { value: "号" } });
    await advance();
    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(screen.getByRole("option", { name: "一号" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.keyDown(input, { key: "ArrowUp" });
    expect(screen.getByRole("option", { name: "二号" })).toHaveAttribute(
      "aria-selected",
      "true",
    );

    expect(fireEvent.keyDown(input, { key: "Enter" })).toBe(false);
    expect(input).toHaveValue("二号");
    expect(submit).not.toHaveBeenCalled();
    fireEvent.submit(form);
    expect(submit).toHaveBeenCalledTimes(1);
  });

  it("selects with mouse down without losing input focus", async () => {
    vi.mocked(searchService.suggest).mockResolvedValue({ items: ["星空札记"] });
    render(<SearchAutocompleteForm initialQuery="" tags={[]} sort="relevance" />);
    const input = screen.getByRole("combobox", { name: "搜索文章" });
    input.focus();
    fireEvent.change(input, { target: { value: "星" } });
    await advance();

    fireEvent.mouseDown(screen.getByRole("option", { name: "星空札记" }));

    expect(input).toHaveFocus();
    expect(input).toHaveValue("星空札记");
    expect(input).toHaveAttribute("aria-expanded", "false");
  });

  it("closes on Escape and submits normally when there is no active option", async () => {
    const { container } = render(
      <SearchAutocompleteForm initialQuery="普通关键词" tags={[]} sort="relevance" />,
    );
    const input = screen.getByRole("combobox", { name: "搜索文章" });
    fireEvent.focus(input);
    fireEvent.keyDown(input, { key: "Escape" });
    expect(input).toHaveAttribute("aria-expanded", "false");

    const event = new KeyboardEvent("keydown", {
      key: "Enter",
      bubbles: true,
      cancelable: true,
    });
    input.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
    expect(container.querySelector('input[name="q"]')).toHaveValue("普通关键词");
  });

  it("silently clears suggestions and restores changed props after a keyed navigation", async () => {
    vi.mocked(searchService.suggest).mockResolvedValueOnce({ items: ["旧建议"] });
    const { container, rerender } = render(
      <SearchAutocompleteForm
        key="old-search"
        initialQuery=""
        tags={["旧标签"]}
        sort="newest"
      />,
    );
    const input = screen.getByRole("combobox");
    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: "旧" } });
    await advance();
    expect(screen.getByRole("option", { name: "旧建议" })).toBeVisible();

    rerender(
      <SearchAutocompleteForm
        key="new-search"
        initialQuery="新"
        tags={["新标签"]}
        sort="relevance"
      />,
    );
    expect(screen.getByRole("combobox", { name: "搜索文章" })).toHaveValue("新");
    expect(container.querySelector('input[name="tags"]')).toHaveValue("新标签");
    expect(container.querySelector('input[name="sort"]')).toHaveValue("relevance");

    vi.mocked(searchService.suggest).mockRejectedValueOnce(new Error("offline"));
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "失败" } });
    await advance();
    expect(screen.queryByRole("option")).not.toBeInTheDocument();
  });
});
