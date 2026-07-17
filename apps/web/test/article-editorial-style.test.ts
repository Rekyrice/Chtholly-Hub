import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const page = readFileSync("app/(site)/post/[slug]/page.tsx", "utf8");
const article = readFileSync("app/styles/article.css", "utf8");
const visuals = readFileSync("app/styles/route-visuals.css", "utf8");
const responsive = readFileSync("app/styles/responsive.css", "utf8");

describe("article editorial reading style", () => {
  it("uses a 920/28/312 desktop grid and one continuous translucent reading surface", () => {
    expect(page).toContain('className="post-card article-detail-card"');
    expect(article).toMatch(
      /\.article-detail-layout\s*\{[^}]*width:\s*min\(1260px, calc\(100vw - 32px\)\);[^}]*max-width:\s*1260px;[^}]*margin:\s*0 auto;[^}]*left:\s*50%;[^}]*transform:\s*translateX\(-50%\);[^}]*grid-template-columns:\s*minmax\(0, 920px\);/,
    );
    expect(article).toMatch(/\.article-main\s*\{[^}]*max-width:\s*920px;/);
    expect(article).toMatch(
      /@media \(min-width:\s*1024px\)[\s\S]*?\.article-detail-layout\s*\{[^}]*grid-template-columns:\s*minmax\(0, 920px\) 312px;[^}]*gap:\s*28px;/,
    );
    expect(article).toMatch(
      /\.article-detail-card\s*\{[^}]*background:\s*color-mix\([^}]*transparent[^}]*backdrop-filter:\s*blur/,
    );
    expect(visuals).toMatch(
      /\.site-shell--route-visual \.article-detail-card\s*\{[^}]*--surface-reading-alpha/,
    );
  });

  it("aligns the header with a centered 760px body and keeps the first paragraph away from the divider", () => {
    expect(article).toMatch(
      /\.article-detail-card \.entry-header\s*\{[^}]*padding:\s*52px 80px 28px;/,
    );
    expect(article).toMatch(
      /\.article-detail-card \.prose-anime\s*\{[^}]*width:\s*calc\(100% - 160px\);[^}]*max-width:\s*760px;[^}]*margin:\s*0 auto;[^}]*padding:\s*44px 0 56px;[^}]*border-top:/,
    );
  });

  it("renders four adjacent sidebar surfaces with sticky desktop overflow protection", () => {
    expect(article).toMatch(
      /\.article-reading-sidebar\s*\{[^}]*display:\s*grid;[^}]*gap:\s*18px;/,
    );
    expect(article).toMatch(
      /\.article-reading-sidebar--compact\s*\{[^}]*gap:\s*12px;/,
    );
    expect(article).toMatch(
      /\.article-reading-navigator__progress-block,[\s\S]*?\.article-reading-sidebar__section\s*\{[^}]*border-radius:\s*14px;[^}]*backdrop-filter:\s*blur/,
    );
    expect(article).toMatch(
      /\.article-reading-sidebar:not\(\.article-reading-sidebar--compact\)\s*\{[^}]*position:\s*sticky;[^}]*top:\s*76px;[^}]*max-height:\s*calc\(100vh - 92px\);[^}]*overflow-y:\s*auto;/,
    );
    expect(responsive).toMatch(
      /@media \(max-width:\s*640px\)[\s\S]*?\.article-reading-sidebar:not\(\.article-reading-sidebar--compact\)\s*\{[^}]*gap:\s*14px;/,
    );
  });

  it("renders only image-adjacent captions smaller, narrower, and centered", () => {
    expect(article).toMatch(
      /\.prose-anime \.article-image-caption\s*\{[^}]*max-width:\s*88%;[^}]*font-size:\s*0\.8rem;[^}]*line-height:\s*1\.55;[^}]*text-align:\s*center;/,
    );
    expect(article).not.toContain(":has(> img:only-child)");
  });

  it("switches to 48px tablet insets and 16px mobile insets", () => {
    expect(responsive).toMatch(
      /@media \(max-width:\s*1023px\)[\s\S]*?\.article-detail-card \.entry-header\s*\{[^}]*padding:\s*40px 48px 28px;/,
    );
    expect(responsive).toMatch(
      /@media \(max-width:\s*1023px\)[\s\S]*?\.article-detail-card \.prose-anime\s*\{[^}]*padding:\s*44px 48px 56px;/,
    );
    expect(responsive).toMatch(
      /@media \(max-width:\s*640px\)[\s\S]*?\.article-detail-card \.prose-anime\s*\{[^}]*padding:\s*40px 16px 44px;/,
    );
  });
});
