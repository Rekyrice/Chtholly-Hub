import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import SiteChrome from "@/components/site/SiteChrome";

const navigation = vi.hoisted(() => ({ pathname: "/hub" }));

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
}));

vi.mock("@/components/site/Footer", () => ({ default: () => <div>footer</div> }));
vi.mock("@/components/site/MobileBottomNav", () => ({ default: () => <div>mobile-nav</div> }));
vi.mock("@/components/site/Navbar", () => ({ default: () => <div>navbar</div> }));
vi.mock("@/components/site/SiteHeader", () => ({ default: () => <div>site-header</div> }));
vi.mock("@/components/agent/AgentPageBackground", () => ({ default: () => <div>agent-background</div> }));
vi.mock("@/components/agent/FloatingAgent", () => ({
  default: () => <div data-testid="site-chrome-floating-agent" />,
}));

describe("SiteChrome", () => {
  beforeEach(() => {
    navigation.pathname = "/hub";
  });

  it("does not own the floating Agent runtime on ordinary pages", () => {
    render(<SiteChrome>content</SiteChrome>);

    expect(screen.getByText("content")).toBeInTheDocument();
    expect(screen.queryByTestId("site-chrome-floating-agent")).not.toBeInTheDocument();
  });
});
