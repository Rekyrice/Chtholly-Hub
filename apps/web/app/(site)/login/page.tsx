"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { ApiError } from "@/lib/services/apiClient";
import { authService } from "@/lib/services/authService";
import { siteConfig } from "@/lib/site.config";

type Mode = "login" | "register";

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<Mode>("login");
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [countdown, setCountdown] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const sendCode = async () => {
    if (!phone.trim()) {
      setError("请输入手机号");
      return;
    }
    setError("");
    try {
      const res = await authService.sendCode({
        identifier: phone.trim(),
        scene: mode === "login" ? "LOGIN" : "REGISTER",
      });
      setCountdown(res.expireSeconds > 60 ? 60 : res.expireSeconds);
      const timer = setInterval(() => {
        setCountdown((c) => {
          if (c <= 1) {
            clearInterval(timer);
            return 0;
          }
          return c - 1;
        });
      }, 1000);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "发送验证码失败");
    }
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError("");
    try {
      if (mode === "login") {
        await authService.loginWithCode(phone.trim(), code.trim());
      } else {
        await authService.register(phone.trim(), code.trim());
      }
      window.dispatchEvent(new Event("chtholly-auth-change"));
      router.push("/write");
      router.refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "登录失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-md mx-auto post-card p-8">
      <h1 className="entry-title entry-title-single text-center mb-2">Login</h1>
      <p className="text-center mb-8" style={{ color: "#727272", fontSize: 14 }}>
        手机号验证码登录 · {siteConfig.name}
      </p>

      <div className="flex gap-2 mb-6 justify-center">
        {(["login", "register"] as Mode[]).map((m) => (
          <button
            key={m}
            type="button"
            onClick={() => setMode(m)}
            style={{
              padding: "6px 16px",
              fontSize: 13,
              border: "none",
              cursor: "pointer",
              background: mode === m ? siteConfig.theme.primary : "#f5f5f5",
              color: mode === m ? "#fff" : "#424242",
            }}
          >
            {m === "login" ? "登录" : "注册"}
          </button>
        ))}
      </div>

      <form onSubmit={onSubmit} className="space-y-4">
        <div>
          <label className="block mb-1 text-sm" style={{ color: "#616161" }}>
            手机号
          </label>
          <input
            type="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            placeholder="11 位手机号"
            className="w-full px-3 py-2 border"
            style={{ borderColor: "#e0e0e0", fontSize: 16 }}
            required
          />
        </div>

        <div>
          <label className="block mb-1 text-sm" style={{ color: "#616161" }}>
            验证码
          </label>
          <div className="flex gap-2">
            <input
              type="text"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="6 位验证码"
              className="flex-1 px-3 py-2 border"
              style={{ borderColor: "#e0e0e0", fontSize: 16 }}
              required
            />
            <button
              type="button"
              disabled={countdown > 0}
              onClick={() => void sendCode()}
              style={{
                padding: "0 12px",
                fontSize: 13,
                whiteSpace: "nowrap",
                background: siteConfig.theme.primary,
                color: "#fff",
                border: "none",
                cursor: countdown > 0 ? "not-allowed" : "pointer",
                opacity: countdown > 0 ? 0.6 : 1,
              }}
            >
              {countdown > 0 ? `${countdown}s` : "获取验证码"}
            </button>
          </div>
          <p className="mt-1 text-xs" style={{ color: "#9e9e9e" }}>
            开发环境验证码见后端日志（LoggingCodeSender）
          </p>
        </div>

        {error && (
          <p className="text-sm" style={{ color: "#d32f2f" }}>
            {error}
          </p>
        )}

        <button
          type="submit"
          disabled={loading}
          className="w-full py-2.5 text-white uppercase tracking-wide"
          style={{
            background: siteConfig.theme.primary,
            border: "none",
            cursor: loading ? "wait" : "pointer",
            opacity: loading ? 0.7 : 1,
          }}
        >
          {loading ? "处理中…" : mode === "login" ? "登录" : "注册并登录"}
        </button>
      </form>
    </div>
  );
}
