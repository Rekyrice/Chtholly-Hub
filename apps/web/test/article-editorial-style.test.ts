import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const page = readFileSync("app/(site)/post/[slug]/page.tsx", "utf8");
const article = readFileSync("app/styles/article.css", "utf8");
const visuals = readFileSync("app/styles/route-visuals.css", "utf8");
const responsive = readFileSync("app/styles/responsive.css", "utf8");

describe("article editorial reading style", () => {
  it("uses one continuous article surface with a shared content baseline", () => {
    expect(page).toContain('className="post-card article-detail-card"');
    expect(article).toMatch(
      /\.article-detail-card \.article-reading-sidebar--compact\s*\{[^}]*margin:\s*0 72px 32px;/,
    );
    expect(article).toMatch(
      /\.article-detail-card \.prose-anime\s*\{[^}]*padding:\s*32px 72px 48px;[^}]*background:\s*transparent;/,
    );
    expect(visuals).toMatch(
      /\.site-shell--route-visual \.article-detail-card\s*\{[^}]*--surface-reading-alpha/,
    );
  });

  it("renders an image caption smaller and centered", () => {
    expect(article).toMatch(
      /\.prose-anime p:has\(> img:only-child\) \+ p:has\(> em:only-child\)\s*\{[^}]*font-size:\s*0\.78em;[^}]*text-align:\s*center;/,
    );
  });

  it("preserves the aligned reading inset on tablet and mobile", () => {
    expect(responsive).toMatch(
      /\.article-detail-card \.prose-anime\s*\{[^}]*padding:\s*28px 24px 40px;/,
    );
    expect(responsive).toMatch(
      /\.article-detail-card \.prose-anime\s*\{[^}]*padding:\s*24px 16px 36px;/,
    );
  });
});
