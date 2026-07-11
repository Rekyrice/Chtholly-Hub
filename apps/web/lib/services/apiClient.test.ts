import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiFetch } from "@/lib/services/apiClient";

function stubResponse(body: BodyInit, init: ResponseInit): void {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(body, init)));
}

async function captureApiError(request: Promise<unknown>): Promise<ApiError> {
  try {
    await request;
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError);
    return error as ApiError;
  }
  throw new Error("Expected apiFetch to reject");
}

describe("apiFetch error messages", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("does not expose an HTML error page as the API error message", async () => {
    const html = `<!doctype html><html><body>${"not-found".repeat(300)}</body></html>`;
    stubResponse(html, {
      status: 404,
      headers: { "Content-Type": "text/html; charset=utf-8" },
    });

    const error = await captureApiError(
      apiFetch("/api/v1/admin/stats", { accessToken: null }),
    );

    expect(error).toMatchObject({
      status: 404,
      message: "请求失败（404）",
    });
    expect(error.message).not.toContain("<!doctype");
  });

  it("preserves a short plain-text error message", async () => {
    stubResponse("操作失败，请稍后重试", {
      status: 409,
      headers: { "Content-Type": "text/plain; charset=utf-8" },
    });

    const error = await captureApiError(apiFetch("/api/example", { accessToken: null }));

    expect(error.message).toBe("操作失败，请稍后重试");
  });

  it("preserves a JSON business error message", async () => {
    stubResponse(JSON.stringify({ message: "业务错误" }), {
      status: 422,
      headers: { "Content-Type": "application/json" },
    });

    const error = await captureApiError(apiFetch("/api/example", { accessToken: null }));

    expect(error.message).toBe("业务错误");
  });

  it("does not expose a JSON object without a readable message", async () => {
    stubResponse(JSON.stringify({ message: { internal: "detail" }, traceId: "abc" }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });

    const error = await captureApiError(apiFetch("/api/example", { accessToken: null }));

    expect(error.message).toBe("请求失败（500）");
  });

  it("normalizes whitespace and truncates an overlong error message", async () => {
    const message = `  ${"无断点错误".repeat(70)} \n\t 请稍后重试  `;
    stubResponse(JSON.stringify({ message }), {
      status: 400,
      headers: { "Content-Type": "application/json" },
    });

    const error = await captureApiError(apiFetch("/api/example", { accessToken: null }));

    expect(error.message).toHaveLength(240);
    expect(error.message).toMatch(/…$/u);
    expect(error.message).not.toMatch(/\s{2,}/u);
  });

  it("does not split an emoji surrogate pair at the truncation boundary", async () => {
    const message = `${"a".repeat(238)}😀tail`;
    stubResponse(JSON.stringify({ message }), {
      status: 400,
      headers: { "Content-Type": "application/json" },
    });

    const error = await captureApiError(apiFetch("/api/example", { accessToken: null }));

    expect(error.message).toBe(`${"a".repeat(238)}…`);
    expect(() => encodeURIComponent(error.message)).not.toThrow();
  });

  it("rejects an HTML-looking body even without a content type", async () => {
    stubResponse("  <!DOCTYPE html><html><body>Not Found</body></html>", { status: 404 });

    const error = await captureApiError(apiFetch("/api/example", { accessToken: null }));

    expect(error.message).toBe("请求失败（404）");
  });

  it("rejects XHTML responses based on their content type", async () => {
    stubResponse("Gateway error", {
      status: 502,
      headers: { "Content-Type": "application/xhtml+xml; charset=utf-8" },
    });

    const error = await captureApiError(apiFetch("/api/example", { accessToken: null }));

    expect(error.message).toBe("请求失败（502）");
  });

  it.each([
    "<!-- proxy error --><html><body>Not Found</body></html>",
    '<?xml version="1.0"?><html><body>Not Found</body></html>',
    "<title>502 Bad Gateway</title>",
  ])("rejects an HTML error page after a safe leading prefix: %s", async (body) => {
    stubResponse(body, { status: 502 });

    const error = await captureApiError(apiFetch("/api/example", { accessToken: null }));

    expect(error.message).toBe("请求失败（502）");
  });

  it("preserves ordinary text that only mentions an HTML tag later", async () => {
    const body = "上游返回提示：<title> 字段缺失";
    stubResponse(body, {
      status: 400,
      headers: { "Content-Type": "text/plain" },
    });

    const error = await captureApiError(apiFetch("/api/example", { accessToken: null }));

    expect(error.message).toBe(body);
  });

  it("does not reject plain text for a content type that only contains the HTML token", async () => {
    const body = "普通安全错误消息";
    stubResponse(body, {
      status: 400,
      headers: { "Content-Type": "text/htmlish; charset=utf-8" },
    });

    const error = await captureApiError(apiFetch("/api/example", { accessToken: null }));

    expect(error.message).toBe(body);
  });

  it("keeps the token-specific message for authenticated 401 responses", async () => {
    stubResponse("Unauthorized", {
      status: 401,
      headers: { "Content-Type": "text/plain" },
    });

    const error = await captureApiError(
      apiFetch("/api/example", {
        accessToken: "expired-token",
        skipAuthRefresh: true,
      }),
    );

    expect(error.message).toBe("登录已过期，请重新登录");
  });
});
