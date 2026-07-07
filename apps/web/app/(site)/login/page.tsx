"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { Button } from "@/components/ui/Button";
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
        className="field-input pr-16"
        required={props.required}
        minLength={props.minLength}
      />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        className="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-sky bg-transparent border-0 cursor-pointer transition-colors duration-150 hover:text-sky-deep"
      >
        {visible ? "隐藏" : "显示"}
      </button>
    </div>
  );
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <Button
      type="button"
      variant={active ? "primary" : "ghost"}
      size="sm"
      onClick={onClick}
    >
      {children}
    </Button>
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
    <div className="max-w-md mx-auto post-card p-8" data-testid="login-page">
      <h1 className="entry-title entry-title-single text-center mb-2">Login</h1>
      <p className="text-center mb-8 text-sm text-text-secondary">
        {mode === "login" ? "密码登录为主 · 验证码备用" : "用户名注册"} · {siteConfig.name}
      </p>

      <div className="flex gap-2 mb-6 justify-center">
        {(["login", "register"] as Mode[]).map((m) => (
          <TabButton
            key={m}
            active={mode === m}
            onClick={() => {
              setMode(m);
              setError("");
            }}
          >
            {m === "login" ? "登录" : "注册"}
          </TabButton>
        ))}
      </div>

      {mode === "login" && (
        <div className="flex gap-2 mb-6 justify-center">
          {(["password", "code"] as LoginMethod[]).map((m) => (
            <TabButton
              key={m}
              active={loginMethod === m}
              onClick={() => {
                setLoginMethod(m);
                setError("");
              }}
            >
              {m === "password" ? "密码登录" : "验证码登录"}
            </TabButton>
          ))}
        </div>
      )}

      <form onSubmit={onSubmit} className="space-y-4">
        {mode === "login" && loginMethod === "password" && (
          <>
            <div>
              <label className="field-label">用户名或手机号</label>
              <input
                type="text"
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                placeholder="用户名或手机号"
                className="field-input"
                required
              />
            </div>
            <div>
              <label className="field-label">密码</label>
              <PasswordInput value={password} onChange={setPassword} placeholder="登录密码" required />
              <div className="mt-2 text-right text-xs">
                <Link href="/reset-password" className="text-sky transition-colors duration-150 hover:text-sky-deep">
                  忘记密码？
                </Link>
              </div>
            </div>
          </>
        )}

        {mode === "login" && loginMethod === "code" && (
          <>
            <div>
              <label className="field-label">手机号</label>
              <input
                type="tel"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                placeholder="11 位手机号"
                className="field-input"
                required
              />
            </div>
            <div>
              <label className="field-label">验证码</label>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  placeholder="6 位验证码"
                  className="field-input flex-1"
                  required
                />
                <Button
                  type="button"
                  size="sm"
                  disabled={countdown > 0}
                  onClick={() => void sendCode()}
                  className="whitespace-nowrap"
                >
                  {countdown > 0 ? `${countdown}s` : "获取验证码"}
                </Button>
              </div>
              <p className="mt-1 text-xs text-hint">
                开发环境验证码见后端日志（LoggingCodeSender）
              </p>
            </div>
          </>
        )}

        {mode === "register" && (
          <>
            <div>
              <label className="field-label">
                用户名 <span className="text-error">*</span>
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
                className="field-input"
                required
              />
              {handleHint && <p className="mt-1 text-xs text-error">{handleHint}</p>}
            </div>
            <div>
              <label className="field-label">昵称（可选）</label>
              <input
                type="text"
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                placeholder="不填则自动生成"
                className="field-input"
              />
            </div>
            <div>
              <label className="field-label">
                密码 <span className="text-error">*</span>
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
              <label className="field-label">
                确认密码 <span className="text-error">*</span>
              </label>
              <PasswordInput
                value={confirmPassword}
                onChange={setConfirmPassword}
                placeholder="再次输入密码"
                required
                minLength={8}
              />
            </div>
            <label className="flex items-start gap-2 text-sm text-text-secondary">
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

        {error && <p className="text-sm text-error">{error}</p>}

        <Button
          type="submit"
          loading={loading}
          size="lg"
          className="w-full tracking-wide"
          data-testid="login-submit"
        >
          {mode === "login" ? "登录" : "注册"}
        </Button>
      </form>

      <p className="text-center mt-6 text-sm text-text-secondary">
        {mode === "login" ? (
          <>
            没有账号？{" "}
            <button
              type="button"
              onClick={() => setMode("register")}
              className="text-sky bg-transparent border-0 cursor-pointer transition-colors duration-150 hover:text-sky-deep"
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
              className="text-sky bg-transparent border-0 cursor-pointer transition-colors duration-150 hover:text-sky-deep"
            >
              去登录
            </button>
          </>
        )}
      </p>
    </div>
  );
}
