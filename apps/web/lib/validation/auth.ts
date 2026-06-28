/** 认证相关前端校验，对齐后端 AuthService / IdentifierValidator 规则 */

const PHONE_PATTERN = /^1\d{10}$/;
const HANDLE_PATTERN = /^[a-zA-Z_][a-zA-Z0-9_]{2,31}$/;

export function isValidPhone(value: string): boolean {
  return PHONE_PATTERN.test(value.trim());
}

export function isValidHandle(value: string): boolean {
  return HANDLE_PATTERN.test(value.trim());
}

export function isValidPassword(value: string): boolean {
  const trimmed = value.trim();
  if (trimmed.length < 8) return false;
  return /[A-Za-z]/.test(trimmed) && /\d/.test(trimmed);
}

export function resolvePasswordLoginType(identifier: string): "PHONE" | "HANDLE" {
  return isValidPhone(identifier) ? "PHONE" : "HANDLE";
}

export function validateHandleField(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return "请输入用户名";
  if (!isValidHandle(trimmed)) {
    return "用户名需 3-32 字符，仅支持字母、数字、下划线，且不能以数字开头";
  }
  return null;
}

export function validatePasswordField(value: string): string | null {
  if (!value) return "请输入密码";
  if (!isValidPassword(value)) return "密码至少 8 位，且需包含字母和数字";
  return null;
}

export function validatePasswordConfirm(password: string, confirm: string): string | null {
  if (password !== confirm) return "两次输入的密码不一致";
  return null;
}
