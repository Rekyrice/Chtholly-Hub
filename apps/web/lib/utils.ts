import { clsx, type ClassValue } from "clsx";
import { format } from "date-fns";
import { enUS, zhCN } from "date-fns/locale";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatDate(date: string) {
  return format(new Date(date), "yyyy年MM月dd日", { locale: zhCN });
}

export function formatDateEnglish(date: string) {
  return format(new Date(date), "MMMM d, yyyy", { locale: enUS });
}

/** 归档页：年 + 月分组键 */
export function archiveGroupKey(isoDate: string) {
  const d = new Date(isoDate);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

export function formatArchiveMonth(isoDate: string) {
  return format(new Date(isoDate), "yyyy年 MMMM", { locale: zhCN });
}
