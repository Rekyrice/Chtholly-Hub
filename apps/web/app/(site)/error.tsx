"use client";

import { useEffect } from "react";

export default function SiteError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("[Chtholly Hub]", error);
  }, [error]);

  return (
    <div className="post-card p-16 text-center max-w-xl mx-auto my-12">
      <h1 className="entry-title" style={{ fontSize: 24 }}>
        页面加载失败
      </h1>
      <p style={{ color: "#727272", margin: "16px 0 24px", lineHeight: 1.7 }}>
        无法连接后端或数据暂时不可用。请确认 Spring Boot 已在{" "}
        <code style={{ background: "#f5f5f5", padding: "2px 6px" }}>
          localhost:8080
        </code>{" "}
        运行，然后重试。
      </p>
      <button
        type="button"
        onClick={() => reset()}
        style={{
          padding: "8px 20px",
          fontSize: 14,
          color: "#fff",
          backgroundColor: "#009688",
          border: "none",
          cursor: "pointer",
          letterSpacing: 1,
        }}
      >
        重试
      </button>
    </div>
  );
}
