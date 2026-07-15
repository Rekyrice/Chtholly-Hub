import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const feed = readFileSync("app/styles/feed.css", "utf8");
const admin = readFileSync("app/styles/admin.css", "utf8");

describe("hub recommendation and admin heading style", () => {
  it("keeps every recommendation slide at a stable desktop height", () => {
    expect(feed).toMatch(
      /\.hub-recommendation__slide\s*\{[^}]*block-size:\s*310px;[^}]*overflow:\s*hidden;/,
    );
    expect(feed).toMatch(
      /\.hub-recommendation__content\s*\{[^}]*min-height:\s*0;[^}]*overflow:\s*hidden;/,
    );
    expect(feed).toMatch(
      /\.hub-recommendation__content h3\s*\{[^}]*-webkit-line-clamp:\s*2;/,
    );
    expect(feed).toMatch(
      /\.hub-recommendation__quote\s*\{[^}]*-webkit-line-clamp:\s*3;/,
    );
  });

  it("places every admin page heading on a readable shared surface", () => {
    expect(admin).toMatch(
      /\.admin-page__header\s*\{[^}]*padding:\s*18px 20px;[^}]*background:\s*color-mix\([^}]*backdrop-filter:\s*blur\(14px\)/,
    );
    expect(admin).toMatch(
      /\.admin-page__header h1\s*\{[^}]*text-shadow:/,
    );
  });
});
