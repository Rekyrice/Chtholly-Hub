"use client";

import { useEffect } from "react";
import { Button } from "@/components/ui/Button";

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
      <h1 className="entry-title text-2xl">页面加载失败</h1>
      <p className="text-text-secondary my-4 leading-relaxed">
        无法连接后端或数据暂时不可用。请确认 Spring Boot 已在{" "}
        <code className="bg-cloud px-1.5 py-0.5 rounded text-sm">localhost:8888</code>{" "}
        运行，然后重试。
      </p>
      <Button type="button" onClick={() => reset()} className="tracking-wide">
        重试
      </Button>
    </div>
  );
}
