"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { ApiError } from "@/lib/services/apiClient";
import { authService } from "@/lib/services/authService";
import {
  isValidPhone,
  validatePasswordConfirm,
  validatePasswordField,
} from "@/lib/validation/auth";

function PasswordInput({
  value,
  onChange,
  placeholder,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
}) {
  const [visible, setVisible] = useState(false);
  return (
    <div className="relative">
      <input
        type={visible ? "text" : "password"}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        className="field-input pr-16"
        minLength={8}
        required
      />
      <button
        type="button"
        onClick={() => setVisible((next) => !next)}
        className="absolute right-2 top-1/2 -translate-y-1/2 text-xs text-sky bg-transparent border-0 cursor-pointer transition-colors duration-150 hover:text-sky-deep"
      >
        {visible ? "隐藏" : "显示"}
      </button>
    </div>
  );
}

export default function ResetPasswordPage() {
  const router = useRouter();
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [countdown, setCountdown] = useState(0);
  const [sending, setSending] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  useEffect(() => {
    if (countdown <= 0) return undefined;
    const timer = window.setInterval(() => {
      setCountdown((current) => Math.max(0, current - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [countdown]);

  const sendCode = async () => {
    const trimmedPhone = phone.trim();
    if (!isValidPhone(trimmedPhone)) {
      setError("请输入有效的 11 位手机号");
      return;
    }

    setSending(true);
    setError("");
    setSuccess("");
    try {
      const response = await authService.sendCode({
        identifier: trimmedPhone,
        scene: "RESET_PASSWORD",
      });
      setCountdown(Math.min(response.expireSeconds, 60));
      setSuccess("验证码已发送，请查看短信或开发环境日志。");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "验证码发送失败");
    } finally {
      setSending(false);
    }
  };

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmedPhone = phone.trim();
    const trimmedCode = code.trim();

    if (!isValidPhone(trimmedPhone)) {
      setError("请输入有效的 11 位手机号");
      return;
    }
    if (!/^\d{6}$/.test(trimmedCode)) {
      setError("验证码需要是 6 位数字");
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

    setSubmitting(true);
    setError("");
    setSuccess("");
    try {
      await authService.resetPassword(trimmedPhone, trimmedCode, password);
      setSuccess("密码已重置，正在带你回登录页。");
      window.setTimeout(() => {
        router.push("/login");
      }, 700);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "密码重置失败");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="max-w-md mx-auto post-card p-8" data-testid="reset-password-page">
      <h1 className="entry-title entry-title-single text-center mb-2">Reset Password</h1>
      <p className="text-center mb-8 text-sm text-text-secondary">
        输入手机号和验证码，重新设置你的登录密码。
      </p>

      <form onSubmit={onSubmit} className="space-y-4">
        <div>
          <label className="field-label">手机号</label>
          <input
            type="tel"
            value={phone}
            onChange={(event) => setPhone(event.target.value)}
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
              inputMode="numeric"
              value={code}
              onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
              placeholder="6 位验证码"
              className="field-input flex-1"
              required
            />
            <Button
              type="button"
              size="sm"
              disabled={countdown > 0 || sending}
              onClick={() => void sendCode()}
              className="whitespace-nowrap"
            >
              {sending ? "发送中..." : countdown > 0 ? `${countdown}s` : "获取验证码"}
            </Button>
          </div>
        </div>

        <div>
          <label className="field-label">新密码</label>
          <PasswordInput
            value={password}
            onChange={setPassword}
            placeholder="至少 8 位，包含字母和数字"
          />
        </div>

        <div>
          <label className="field-label">确认新密码</label>
          <PasswordInput
            value={confirmPassword}
            onChange={setConfirmPassword}
            placeholder="再次输入新密码"
          />
        </div>

        {error && <p className="text-sm text-error">{error}</p>}
        {success && <p className="text-sm text-sky">{success}</p>}

        <Button
          type="submit"
          loading={submitting}
          size="lg"
          className="w-full tracking-wide"
          data-testid="reset-password-submit"
        >
          重置密码
        </Button>
      </form>

      <p className="text-center mt-6 text-sm text-text-secondary">
        想起来了？{" "}
        <Link href="/login" className="text-sky transition-colors duration-150 hover:text-sky-deep">
          返回登录
        </Link>
      </p>
    </div>
  );
}
