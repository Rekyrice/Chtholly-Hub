import { describe, expect, it } from "vitest";
import { parseRemoteImageOrigin, resolveSiteUrl } from "@/lib/site-url";

describe("site URL configuration", () => {
  it("resolves a configured site URL", () => {
    expect(resolveSiteUrl("https://hub.example.com/")).toEqual(
      new URL("https://hub.example.com/"),
    );
  });

  it("falls back to localhost for an invalid site URL", () => {
    expect(resolveSiteUrl("not a URL")).toEqual(
      new URL("http://localhost:3000/"),
    );
  });

  it("falls back to localhost for an empty site URL", () => {
    expect(resolveSiteUrl("")).toEqual(new URL("http://localhost:3000/"));
  });

  it("parses an image origin into a Next.js remote pattern", () => {
    expect(parseRemoteImageOrigin("https://cdn.example.com/path")).toEqual({
      protocol: "https",
      hostname: "cdn.example.com",
      port: "",
      pathname: "/**",
    });
  });

  it.each([undefined, "", "not a URL", "ftp://cdn.example.com"])(
    "rejects unsupported remote image origin %s",
    (value) => {
      expect(parseRemoteImageOrigin(value)).toBeNull();
    },
  );
});
