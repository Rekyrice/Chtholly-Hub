"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { ApiError } from "@/lib/services/apiClient";
import { authService } from "@/lib/services/authService";
import { siteConfig } from "@/lib/site.config";
import {
  isValidPhone,
  validateHandleField,
  validatePasswordConfirm,
  validatePasswordField,
} from "@/lib/validation/auth";

type Mode = "login" | "register";
type LoginMethod = "password" | "code";

const tabBtn = (active: boolean) => ({
  padding: "6px 16px",
  fontSize: 13,
  border: "none",
  cursor: "pointer" as const,
  background: active ? siteConfig.theme.primary : "#f5f5f5",
  color: active ? "#fff" : "#424242",
});

const inputStyle = { borderColor: "#e0e0e0", fontSize: 16 };

function PasswordInput(props: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  required?: boolean;
  minLength?: number;
}) {
  const [visible, setVisible] = useState(false);
  return (
    <div className="relative">
      <input
        type={visible ? "text" : "password"}
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
        placeholder={props.placeholder}
        className="w-full px-3 py-2 border pr-16"
        style={inputStyle}
        required={props.required}
        minLength={props.minLength}
      />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        className="absolute right-2 top-1/2 -translate-y-1/2 text-xs"
        style={{ color: siteConfig.theme.primary, background: "none", border: "none", cursor: "pointer" }}
      >
        {visible ? "隐藏" : "显示"}
      </button>
    </div>
  );
}

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<Mode>("login");
  const [loginMethod, setLoginMethod] = useState<LoginMethod>("password");

  const [identifier, setIdentifier] = useState("");
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  const [handle, setHandle] = useState("");
  const [nickname, setNickname] = useState("");
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [handleHint, setHandleHint] = useState<string | null>(null);

  const [countdown, setCountdown] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const afterAuthSuccess = () => {
    window.dispatchEvent(new Event("chtholly-auth-change"));
    router.push("/write");
    router.refresh();
  };

  const sendCode = async () => {
    if (!phone.trim()) {
      setError("请输入手机号");
      return;
    }
    if (!isValidPhone(phone)) {
      setError("手机号格式错误");
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

  const onHandleBlur = () => {
    if (!handle.trim()) {
      setHandleHint(null);
      return;
    }
    setHandleHint(validateHandleField(handle));
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError("");

    try {
      if (mode === "login") {
        if (loginMethod === "password") {
          if (!identifier.trim()) {
            setError("请输入用户名或手机号");
            return;
          }
          if (!password) {
            setError("请输入密码");
            return;
          }
          await authService.loginWithPassword(identifier.trim(), password);
        } else {
          if (!phone.trim()) {
            setError("请输入手机号");
            return;
          }
          if (!code.trim()) {
            setError("请输入验证码");
            return;
          }
          await authService.loginWithCode(phone.trim(), code.trim());
        }
      } else {
        const handleError = validateHandleField(handle);
        if (handleError) {
          setError(handleError);
          return;
        }
        const passwordError = validatePasswordField(password);
        if (passwordError) {
          setError(passwordError);
          return;
        }
        const confirmError = validatePasswordConfirm(password, confirmPassword);
        if (confirmError) {
          setError(confirmError);
          return;
        }
        if (!agreeTerms) {
          setError("请先同意服务条款");
          return;
        }
        await authService.registerWithHandle({
          handle: handle.trim(),
          password,
          nickname: nickname.trim() || undefined,
        });
      }
      afterAuthSuccess();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : mode === "login" ? "登录失败" : "注册失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-md mx-auto post-card p-8">
      <h1 className="entry-title entry-title-single text-center mb-2">Login</h1>
      <p className="text-center mb-8" style={{ color: "#727272", fontSize: 14 }}>
        {mode === "login" ? "密码登录为主 · 验证码备用" : "用户名注册"} · {siteConfig.name}
      </p>

      <div className="flex gap-2 mb-6 justify-center">
        {(["login", "register"] as Mode[]).map((m) => (
          <button
            key={m}
            type="button"
            onClick={() => {
              setMode(m);
              setError("");
            }}
            style={tabBtn(mode === m)}
          >
            {m === "login" ? "登录" : "注册"}
          </button>
        ))}
      </div>

      {mode === "login" && (
        <div className="flex gap-2 mb-6 justify-center">
          {(["password", "code"] as LoginMethod[]).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => {
                setLoginMethod(m);
                setError("");
              }}
              style={tabBtn(loginMethod === m)}
            >
              {m === "password" ? "密码登录" : "验证码登录"}
            </button>
          ))}
        </div>
      )}

      <form onSubmit={onSubmit} className="space-y-4">
        {mode === "login" && loginMethod === "password" && (
          <>
            <div>
              <label className="block mb-1 text-sm" style={{ color: "#616161" }}>
                用户名或手机号
              </label>
              <input
                type="text"
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                placeholder="用户名或手机号"
                className="w-full px-3 py-2 border"
                style={inputStyle}
                required
              />
            </div>
            <div>
              <label className="block mb-1 text-sm" style={{ color: "#616161" }}>
                密码
              </label>
              <PasswordInput value={password} onChange={setPassword} placeholder="登录密码" required />
            </div>
          </>
        )}

        {mode === "login" && loginMethod === "code" && (
          <>
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
                style={inputStyle}
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
                  style={inputStyle}
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
          </>
        )}

        {mode === "register" && (
          <>
            <div>
              <label className="block mb-1 text-sm" style={{ color: "#616161" }}>
                用户名 <span style={{ color: "#d32f2f" }}>*</span>
              </label>
              <input
                type="text"
                value={handle}
                onChange={(e) => {
                  setHandle(e.target.value);
                  if (handleHint) setHandleHint(validateHandleField(e.target.value));
                }}
                onBlur={onHandleBlur}
                placeholder="3-32 字符，字母/数字/下划线"
                className="w-full px-3 py-2 border"
                style={inputStyle}
                required
              />
              {handleHint && (
                <p className="mt-1 text-xs" style={{ color: "#d32f2f" }}>
                  {handleHint}
                </p>
              )}
            </div>
            <div>
              <label className="block mb-1 text-sm" style={{ color: "#616161" }}>
                昵称（可选）
              </label>
              <input
                type="text"
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                placeholder="不填则自动生成"
                className="w-full px-3 py-2 border"
                style={inputStyle}
              />
            </div>
            <div>
              <label className="block mb-1 text-sm" style={{ color: "#616161" }}>
                密码 <span style={{ color: "#d32f2f" }}>*</span>
              </label>
              <PasswordInput
                value={password}
                onChange={setPassword}
                placeholder="至少 8 位，含字母和数字"
                required
                minLength={8}
              />
            </div>
            <div>
              <label className="block mb-1 text-sm" style={{ color: "#616161" }}>
                确认密码 <span style={{ color: "#d32f2f" }}>*</span>
              </label>
              <PasswordInput
                value={confirmPassword}
                onChange={setConfirmPassword}
                placeholder="再次输入密码"
                required
                minLength={8}
              />
            </div>
            <label className="flex items-start gap-2 text-sm" style={{ color: "#616161" }}>
              <input
                type="checkbox"
                checked={agreeTerms}
                onChange={(e) => setAgreeTerms(e.target.checked)}
                className="mt-1"
              />
              <span>我已阅读并同意服务条款</span>
            </label>
          </>
        )}

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
          {loading ? "处理中…" : mode === "login" ? "登录" : "注册"}
        </button>
      </form>

      <p className="text-center mt-6 text-sm" style={{ color: "#757575" }}>
        {mode === "login" ? (
          <>
            没有账号？{" "}
            <button
              type="button"
              onClick={() => setMode("register")}
              style={{ color: siteConfig.theme.primary, background: "none", border: "none", cursor: "pointer" }}
            >
              去注册
            </button>
          </>
        ) : (
          <>
            已有账号？{" "}
            <button
              type="button"
              onClick={() => setMode("login")}
              style={{ color: siteConfig.theme.primary, background: "none", border: "none", cursor: "pointer" }}
            >
              去登录
            </button>
          </>
        )}
      </p>
    </div>
  );
}
