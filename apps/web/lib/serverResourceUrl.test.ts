import { describe, expect, it } from "vitest";
import { resolveServerResourceUrl } from "./serverResourceUrl";

describe("resolveServerResourceUrl", () => {
  it("resolves local-storage paths against the internal API origin", () => {
    expect(
      resolveServerResourceUrl(
        "/uploads/posts/example.md",
        "http://server:8888",
      ),
    ).toBe("http://server:8888/uploads/posts/example.md");
  });

  it("keeps absolute OSS URLs unchanged", () => {
    expect(
      resolveServerResourceUrl(
        "https://bucket.example.com/posts/example.md",
        "http://server:8888",
      ),
    ).toBe("https://bucket.example.com/posts/example.md");
  });
});
