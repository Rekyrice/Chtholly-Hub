import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SiteChrome from "@/components/site/SiteChrome";
import type { VisualBackground } from "@/lib/route-visuals";

const navigation = vi.hoisted(() => ({ pathname: "/hub" }));

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
}));

vi.mock("@/components/site/Footer", () => ({ default: () => <div data-testid="footer" /> }));
vi.mock("@/components/site/MobileBottomNav", () => ({ default: () => <div data-testid="mobile-nav" /> }));
vi.mock("@/components/site/Navbar", () => ({ default: () => <div data-testid="navbar" /> }));
vi.mock("@/components/site/SiteHeader", () => ({
  default: ({ background }: { background?: VisualBackground }) => (
    <div data-testid="site-header">{background?.image ?? "no-header-background"}</div>
  ),
}));
vi.mock("@/components/site/RoutePageBackground", () => ({
  default: ({ background }: { background: VisualBackground }) => (
    <div data-testid="route-page-background">{background.image}</div>
  ),
}));
vi.mock("@/components/agent/AgentPageBackground", () => ({
  default: () => <div data-testid="agent-background" />,
}));
vi.mock("@/components/agent/FloatingAgent", () => ({
  default: () => <div data-testid="site-chrome-floating-agent" />,
}));

describe("SiteChrome", () => {
  afterEach(cleanup);

  beforeEach(() => {
    navigation.pathname = "/hub";
  });

  it("does not own the floating Agent runtime on ordinary pages", () => {
    render(<SiteChrome><span data-testid="content">content</span></SiteChrome>);

    expect(screen.getByTestId("content")).toBeInTheDocument();
    expect(screen.queryByTestId("site-chrome-floating-agent")).not.toBeInTheDocument();
  });

  const routeCases = [
    { path: "/hub", visual: "hub", header: true, footer: true, agentBackground: false },
    { path: "/search", visual: "search", header: true, footer: true, agentBackground: false },
    { path: "/write", visual: "write", header: false, footer: false, agentBackground: false },
    { path: "/agent", visual: null, header: false, footer: false, agentBackground: true },
    { path: "/chtholly", visual: null, header: false, footer: true, agentBackground: false },
    { path: "/admin", visual: null, header: true, footer: true, agentBackground: false },
  ] as const;

  it.each(routeCases)("preserves the shell contract for $path", ({ path, visual, header, footer, agentBackground }) => {
    navigation.pathname = path;
    render(<SiteChrome><span data-testid="content">content</span></SiteChrome>);

    const shell = screen.getByTestId("navbar").parentElement!;
    expect(screen.getByTestId("navbar")).toBeInTheDocument();
    expect(screen.getByRole("main")).toBeInTheDocument();
    expect(screen.getByTestId("mobile-nav")).toBeInTheDocument();
    expect(screen.getByTestId("content")).toBeInTheDocument();
    expect(screen.queryByTestId("site-header") !== null).toBe(header);
    expect(screen.queryByTestId("footer") !== null).toBe(footer);
    expect(screen.queryByTestId("agent-background") !== null).toBe(agentBackground);
    expect(screen.queryByTestId("route-page-background") !== null).toBe(visual !== null);

    if (visual) {
      expect(shell).toHaveClass("site-shell--route-visual");
      expect(shell).toHaveAttribute("data-route-visual", visual);
      expect(screen.getByTestId("route-page-background")).toHaveTextContent(`/${visual === "write" ? "write-workspace" : `${visual}-content`}.webp`);
    } else {
      expect(shell).not.toHaveClass("site-shell--route-visual");
      expect(shell).not.toHaveAttribute("data-route-visual");
    }
  });

  it("renders landing content without the site shell", () => {
    navigation.pathname = "/";
    render(<SiteChrome><span data-testid="content">content</span></SiteChrome>);

    expect(screen.getByTestId("content")).toBeInTheDocument();
    for (const testId of ["navbar", "site-header", "footer", "mobile-nav", "route-page-background"]) {
      expect(screen.queryByTestId(testId)).not.toBeInTheDocument();
    }
    expect(screen.queryByRole("main")).not.toBeInTheDocument();
  });

  it("keeps the ordinary shell and wrapper layers in order", () => {
    navigation.pathname = "/hub";
    render(<SiteChrome><span data-testid="content">content</span></SiteChrome>);

    const shellChildren = Array.from(screen.getByTestId("navbar").parentElement!.children);
    const header = screen.getByTestId("site-header");
    const main = screen.getByRole("main");
    const wrapper = main.parentElement!;
    expect(shellChildren.indexOf(screen.getByTestId("navbar"))).toBeLessThan(shellChildren.indexOf(header.previousElementSibling!));
    expect(shellChildren.indexOf(header.previousElementSibling!)).toBeLessThan(shellChildren.indexOf(header));
    expect(shellChildren.indexOf(header)).toBeLessThan(shellChildren.indexOf(wrapper));
    expect(shellChildren.indexOf(wrapper)).toBeLessThan(shellChildren.indexOf(screen.getByTestId("mobile-nav")));

    const wrapperChildren = Array.from(wrapper.children);
    expect(wrapperChildren.indexOf(screen.getByTestId("route-page-background"))).toBeLessThan(wrapperChildren.indexOf(main));
    expect(wrapperChildren.indexOf(main)).toBeLessThan(wrapperChildren.indexOf(screen.getByTestId("footer").parentElement!));
  });

  it("keeps focused backgrounds before main and omits the write footer", () => {
    navigation.pathname = "/agent";
    const { rerender } = render(<SiteChrome><span data-testid="content">content</span></SiteChrome>);
    let main = screen.getByRole("main");
    expect(Array.from(main.parentElement!.children).indexOf(screen.getByTestId("agent-background"))).toBeLessThan(
      Array.from(main.parentElement!.children).indexOf(main),
    );

    navigation.pathname = "/write";
    rerender(<SiteChrome><span data-testid="content">content</span></SiteChrome>);
    main = screen.getByRole("main");
    expect(Array.from(main.parentElement!.children).indexOf(screen.getByTestId("route-page-background"))).toBeLessThan(
      Array.from(main.parentElement!.children).indexOf(main),
    );
    expect(screen.queryByTestId("footer")).not.toBeInTheDocument();
  });

  it("preserves agent, write, and ordinary main layout classes", () => {
    navigation.pathname = "/agent";
    const { rerender } = render(<SiteChrome><span data-testid="content">content</span></SiteChrome>);
    let main = screen.getByRole("main");
    expect(main).toHaveClass("h-full", "min-h-0", "overflow-hidden", "py-0", "px-0");

    navigation.pathname = "/write";
    rerender(<SiteChrome><span data-testid="content">content</span></SiteChrome>);
    main = screen.getByRole("main");
    expect(main).toHaveClass("flex-1", "py-0", "px-0");
    expect(main).not.toHaveClass("py-8");
    expect(screen.getByTestId("content").parentElement).toHaveClass("w-full");

    navigation.pathname = "/hub";
    rerender(<SiteChrome><span data-testid="content">content</span></SiteChrome>);
    main = screen.getByRole("main");
    expect(main).toHaveClass("flex-1", "py-8");
    expect(screen.getByTestId("content").parentElement).toHaveClass("max-w-6xl");
  });

  it("passes route hero backgrounds to ordinary headers", () => {
    navigation.pathname = "/hub";
    const { rerender } = render(<SiteChrome><span data-testid="content">content</span></SiteChrome>);
    expect(screen.getByTestId("site-header")).toHaveTextContent("/hub-hero.webp");

    navigation.pathname = "/admin";
    rerender(<SiteChrome><span data-testid="content">content</span></SiteChrome>);
    expect(screen.getByTestId("site-header")).toHaveTextContent("no-header-background");
  });
});
