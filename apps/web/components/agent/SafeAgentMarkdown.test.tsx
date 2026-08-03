import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { SafeAgentMarkdown } from "@/components/agent/SafeAgentMarkdown";

describe("SafeAgentMarkdown", () => {
  it("emits neither an image element nor an image preload for Markdown images", () => {
    const html = renderToStaticMarkup(
      <SafeAgentMarkdown
        content="![sensitive evidence](https://tracker.example/answer.png)"
      />,
    );

    expect(html).not.toContain("<img");
    expect(html).not.toMatch(
      /<link(?=[^>]*rel="preload")(?=[^>]*as="image")[^>]*>/u,
    );
    expect(html).not.toContain("https://tracker.example/answer.png");
    expect(html).toContain("sensitive evidence");
  });
});
