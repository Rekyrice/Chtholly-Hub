"""Verify entrance-animation first-paint behavior in a real Chromium browser.

The script serves a controlled fixture built from the repository's actual
bootstrap and CSS files, so backend/API timing cannot mask paint regressions.
Run from apps/web with: python test/e2e/motion_first_paint.py
"""

import asyncio
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from playwright.async_api import Browser, async_playwright


WEB_ROOT = Path(__file__).resolve().parents[2]


def load_fixture_html():
    bootstrap_source = (WEB_ROOT / "lib/motion-bootstrap.ts").read_text(
        encoding="utf-8"
    )
    bootstrap_match = re.search(
        r"export const MOTION_BOOTSTRAP_SCRIPT = `([\s\S]*?)`;",
        bootstrap_source,
    )
    assert bootstrap_match, "Unable to read MOTION_BOOTSTRAP_SCRIPT"

    css = "\n".join(
        (WEB_ROOT / path).read_text(encoding="utf-8")
        for path in ("app/styles/article.css", "app/styles/responsive.css")
    )
    return f"""<!doctype html>
<html>
  <head>
    <style>{css}</style>
    <script>{bootstrap_match.group(1)}</script>
  </head>
  <body>
    <div id="motion-target" class="animate-in animate-in--ready">content</div>
    <script src="/hydrate.js"></script>
  </body>
</html>"""


class FixtureHandler(BaseHTTPRequestHandler):
    fixture_html = load_fixture_html().encode("utf-8")

    def do_GET(self):
        if self.path == "/hydrate.js":
            body = (
                b"document.getElementById('motion-target')"
                b".classList.add('animate-in--visible');"
            )
            content_type = "text/javascript; charset=utf-8"
        else:
            body = self.fixture_html
            content_type = "text/html; charset=utf-8"

        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format, *_args):
        return


async def inspect_motion_element(page):
    element = page.locator("#motion-target")
    await element.wait_for(state="attached", timeout=10_000)
    return {
        "marker": await page.locator("html").get_attribute("data-motion-ready"),
        "classes": await element.get_attribute("class"),
        "opacity": await element.evaluate("element => getComputedStyle(element).opacity"),
    }


async def verify_without_javascript(browser: Browser, url: str):
    context = await browser.new_context(java_script_enabled=False)
    page = await context.new_page()
    await page.goto(url, wait_until="domcontentloaded", timeout=10_000)
    state = await inspect_motion_element(page)
    assert state["marker"] is None, state
    assert "animate-in--ready" in (state["classes"] or ""), state
    assert state["opacity"] == "1", state
    await context.close()
    return state


async def verify_reduced_motion(browser: Browser, url: str):
    context = await browser.new_context()
    page = await context.new_page()
    await page.emulate_media(reduced_motion="reduce")
    await page.goto(url, wait_until="domcontentloaded", timeout=10_000)
    state = await inspect_motion_element(page)
    assert state["marker"] is None, state
    assert state["opacity"] == "1", state
    await context.close()
    return state


async def verify_without_intersection_observer(browser: Browser, url: str):
    context = await browser.new_context()
    page = await context.new_page()
    await page.add_init_script("delete window.IntersectionObserver")
    await page.goto(url, wait_until="domcontentloaded", timeout=10_000)
    state = await inspect_motion_element(page)
    assert state["marker"] is None, state
    assert state["opacity"] == "1", state
    await context.close()
    return state


async def verify_delayed_hydration(browser: Browser, url: str):
    context = await browser.new_context()
    page = await context.new_page()
    script_blocked = asyncio.Event()
    release_scripts = asyncio.Event()

    async def delay_external_scripts(route):
        if route.request.resource_type == "script":
            script_blocked.set()
            await release_scripts.wait()
        await route.continue_()

    await page.route("**/hydrate.js", delay_external_scripts)
    navigation = asyncio.create_task(
        page.goto(url, wait_until="load", timeout=10_000)
    )

    try:
        await asyncio.wait_for(script_blocked.wait(), timeout=10)
        initial = await inspect_motion_element(page)
        assert initial["marker"] == "true", initial
        assert "animate-in--ready" in (initial["classes"] or ""), initial
        assert "animate-in--visible" not in (initial["classes"] or ""), initial
        assert initial["opacity"] == "0", initial

        pre_hydration_opacities = await page.evaluate(
            """async () => {
              const element = document.querySelector('#motion-target');
              const samples = [];
              for (let index = 0; index < 3; index += 1) {
                await new Promise(requestAnimationFrame);
                samples.push(getComputedStyle(element).opacity);
              }
              return samples;
            }"""
        )
        assert pre_hydration_opacities == ["0", "0", "0"], pre_hydration_opacities
    finally:
        release_scripts.set()

    await navigation
    await page.wait_for_function(
        """() => {
          const element = document.querySelector('#motion-target');
          return element?.classList.contains('animate-in--visible') &&
            getComputedStyle(element).opacity === '1';
        }""",
        timeout=10_000,
    )
    hydrated = await inspect_motion_element(page)
    await context.close()
    return {
        "initial": initial,
        "preHydrationOpacities": pre_hydration_opacities,
        "hydrated": hydrated,
    }


async def run_browser_checks(url: str):
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(headless=True)
        results = {
            "withoutJavaScript": await verify_without_javascript(browser, url),
            "withoutIntersectionObserver": await verify_without_intersection_observer(
                browser, url
            ),
            "reducedMotion": await verify_reduced_motion(browser, url),
            "delayedHydration": await verify_delayed_hydration(browser, url),
        }
        await browser.close()
    return results


def main():
    server = ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    try:
        url = f"http://127.0.0.1:{server.server_port}/"
        results = asyncio.run(run_browser_checks(url))
    finally:
        server.shutdown()
        server.server_close()
        server_thread.join(timeout=5)

    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
