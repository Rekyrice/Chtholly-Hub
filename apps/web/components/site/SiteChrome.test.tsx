import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SiteChrome from "@/components/site/SiteChrome";
import type { PageVisualBackground, RouteVisualConfig } from "@/lib/route-visuals";

const navigation = vi.hoisted(() => ({ pathname: "/hub" }));
const headerEffects = vi.hoisted(() => vi.fn());

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
}));

vi.mock("@/components/site/Footer", () => ({ default: () => <div data-testid="footer" /> }));
vi.mock("@/components/site/MobileBottomNav", () => ({ default: () => <div data-testid="mobile-nav" /> }));
vi.mock("@/components/site/Navbar", () => ({ default: () => <div data-testid="navbar" /> }));
vi.mock("@/components/site/SiteHeader", () => ({
  default: ({ onQuoteChange }: { onQuoteChange?: (index: number) => void }) => (
    <MockHeader onQuoteChange={onQuoteChange} />
  ),
}));

function MockHeader({ onQuoteChange }: { onQuoteChange?: (index: number) => void }) {
  useEffect(() => {
    headerEffects();
    onQuoteChange?.(0);
  }, [onQuoteChange]);
  return (
    <button data-testid="site-header" onClick={() => onQuoteChange?.(2)}>
      header
    </button>
  );
}
vi.mock("@/components/site/RoutePageBackground", () => ({
  default: ({ background, activeIndex }: { background: PageVisualBackground; activeIndex?: number }) => (
    <div data-testid="route-page-background" data-active-index={activeIndex}>
      {background.images[activeIndex ?? 0]}
    </div>
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
    headerEffects.mockClear();
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
    { path: "/admin", visual: "admin", header: true, footer: true, agentBackground: false },
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
      expect(screen.getByTestId("route-page-background")).toHaveAttribute("data-active-index", "0");
    } else {
      expect(shell).not.toHaveClass("site-shell--route-visual");
      expect(shell).not.toHaveAttribute("data-route-visual");
    }

    if (header) {
      expect(shell).toHaveClass("site-shell--with-header");
    } else {
      expect(shell).not.toHaveClass("site-shell--with-header");
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
    const background = screen.getByTestId("route-page-background");
    const header = screen.getByTestId("site-header");
    const main = screen.getByRole("main");
    const wrapper = main.parentElement!;
    expect(shellChildren.indexOf(screen.getByTestId("navbar"))).toBeLessThan(1);
    expect(1).toBeLessThan(shellChildren.indexOf(background));
    expect(shellChildren.indexOf(background)).toBeLessThan(shellChildren.indexOf(header));
    expect(shellChildren.indexOf(header)).toBeLessThan(shellChildren.indexOf(wrapper));
    expect(shellChildren.indexOf(wrapper)).toBeLessThan(shellChildren.indexOf(screen.getByTestId("mobile-nav")));

    const wrapperChildren = Array.from(wrapper.children);
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
    expect(screen.getByTestId("route-page-background")).toBeInTheDocument();
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

  it("drives Hub images from quotes and resets the index when the route changes", () => {
    navigation.pathname = "/hub";
    const { rerender } = render(<SiteChrome><span data-testid="content">content</span></SiteChrome>);
    fireEvent.click(screen.getByTestId("site-header"));
    expect(screen.getByTestId("route-page-background")).toHaveAttribute("data-active-index", "2");

    navigation.pathname = "/search";
    rerender(<SiteChrome><span data-testid="content">content</span></SiteChrome>);
    expect(screen.getByTestId("route-page-background")).toHaveAttribute("data-active-index", "0");

    navigation.pathname = "/hub";
    rerender(<SiteChrome><span data-testid="content">content</span></SiteChrome>);
    expect(screen.getByTestId("route-page-background")).toHaveAttribute("data-active-index", "0");
  });

  it("keeps the quote callback stable when the typewriter reports the same index", () => {
    render(<SiteChrome><span>content</span></SiteChrome>);

    expect(headerEffects).toHaveBeenCalledTimes(1);
  });

  it("uses an explicit visual override", () => {
    const visualOverride: RouteVisualConfig = {
      id: "preview",
      page: {
        images: ["/preview.webp"],
        positionDesktop: "center",
        positionMobile: "center",
        overlayAlpha: 0.2,
        blurPx: 0,
        saturate: 1,
      },
    };

    render(<SiteChrome visualOverride={visualOverride}><span>content</span></SiteChrome>);
    expect(screen.getByTestId("route-page-background")).toHaveTextContent("/preview.webp");
    expect(screen.getByTestId("navbar").parentElement).toHaveAttribute("data-route-visual", "preview");
  });
});
