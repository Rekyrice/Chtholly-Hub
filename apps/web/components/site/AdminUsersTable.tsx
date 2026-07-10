"use client";

import { Search } from "lucide-react";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useAsyncAction } from "@/lib/hooks/useAsyncAction";
import { extractErrorMessage } from "@/lib/hooks/useErrorMessage";
import { adminService } from "@/lib/services/adminService";
import type { AdminUser, PageResponse } from "@/lib/types/admin";
import { cn, formatDate } from "@/lib/utils";

const PAGE_SIZE = 20;
const ROLES = ["USER", "ADMIN"] as const;

function emptyPage(): PageResponse<AdminUser> {
  return {
    items: [],
    total: 0,
    page: 1,
    size: PAGE_SIZE,
    hasMore: false,
  };
}

function userInitial(user: AdminUser) {
  return (user.nickname || user.handle || "U").slice(0, 1).toUpperCase();
}

export default function AdminUsersTable() {
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState("");
  const [appliedKeyword, setAppliedKeyword] = useState("");
  const [data, setData] = useState<PageResponse<AdminUser>>(emptyPage);
  const [busyUserId, setBusyUserId] = useState<number | null>(null);

  const { execute: load, loading, error, setError } = useAsyncAction(
    (nextPage: number, nextKeyword: string) =>
      adminService.getUsers(nextPage, PAGE_SIZE, nextKeyword),
    {
      fallbackError: "用户列表加载失败",
      onSuccess: setData,
    },
  );

  useEffect(() => {
    void load(page, appliedKeyword);
  }, [appliedKeyword, load, page]);

  const maxPage = useMemo(
    () => Math.max(1, Math.ceil((data.total || data.items.length) / PAGE_SIZE)),
    [data.items.length, data.total],
  );

  const updateUser = (userId: number, patch: Partial<AdminUser>) => {
    setData((current) => ({
      ...current,
      items: current.items.map((user) => (user.id === userId ? { ...user, ...patch } : user)),
    }));
  };

  const handleSearch = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setPage(1);
    setAppliedKeyword(keyword);
  };

  const handleRoleChange = async (user: AdminUser, role: string) => {
    if (role === user.role) return;
    setBusyUserId(user.id);
    try {
      await adminService.setUserRole(user.id, role);
      updateUser(user.id, { role });
    } catch (err) {
      setError(extractErrorMessage(err, "角色修改失败"));
    } finally {
      setBusyUserId(null);
    }
  };

  const handleBanToggle = async (user: AdminUser) => {
    setBusyUserId(user.id);
    try {
      if (user.banned) {
        await adminService.unbanUser(user.id);
        updateUser(user.id, { banned: false, bannedAt: null });
      } else {
        await adminService.banUser(user.id);
        updateUser(user.id, { banned: true, bannedAt: new Date().toISOString() });
      }
    } catch (err) {
      setError(extractErrorMessage(err, user.banned ? "解封失败" : "封禁失败"));
    } finally {
      setBusyUserId(null);
    }
  };

  return (
    <div className="admin-page">
      <header className="admin-page__header">
        <div>
          <p className="admin-page__eyebrow">Users</p>
          <h1>用户管理</h1>
          <p>查看用户状态，调整角色，处理封禁与解封。</p>
        </div>
      </header>

      {error && <div className="admin-alert">{error}</div>}

      <section className="admin-panel">
        <div className="admin-toolbar">
          <form className="admin-search" onSubmit={handleSearch}>
            <Search size={16} />
            <input
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              placeholder="搜索昵称或 handle"
            />
            <button type="submit">搜索</button>
          </form>
          <span className="admin-toolbar__meta">共 {data.total || data.items.length} 位用户</span>
        </div>

        <div className="admin-table-wrap" aria-busy={loading}>
          <table className="admin-table">
            <thead>
              <tr>
                <th>用户</th>
                <th>联系方式</th>
                <th>角色</th>
                <th>状态</th>
                <th>加入时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={6} className="admin-table__empty">
                    加载中...
                  </td>
                </tr>
              ) : data.items.length === 0 ? (
                <tr>
                  <td colSpan={6} className="admin-table__empty">
                    没有找到用户
                  </td>
                </tr>
              ) : (
                data.items.map((user) => (
                  <tr key={user.id}>
                    <td>
                      <div className="admin-user-cell">
                        {user.avatar ? (
                          // eslint-disable-next-line @next/next/no-img-element
                          <img src={user.avatar} alt={user.nickname} />
                        ) : (
                          <span>{userInitial(user)}</span>
                        )}
                        <div>
                          <strong>{user.nickname}</strong>
                          <small>@{user.handle || user.id}</small>
                        </div>
                      </div>
                    </td>
                    <td>
                      <div className="admin-muted-stack">
                        <span>{user.email || "-"}</span>
                        <small>{user.phone || ""}</small>
                      </div>
                    </td>
                    <td>
                      <select
                        className="admin-select"
                        value={user.role}
                        disabled={busyUserId === user.id}
                        onChange={(event) => void handleRoleChange(user, event.target.value)}
                      >
                        {ROLES.map((role) => (
                          <option key={role} value={role}>
                            {role}
                          </option>
                        ))}
                      </select>
                    </td>
                    <td>
                      <span
                        className={cn(
                          "admin-badge",
                          user.banned ? "admin-badge--danger" : "admin-badge--ok",
                        )}
                      >
                        {user.banned ? "已封禁" : "正常"}
                      </span>
                    </td>
                    <td>{user.createdAt ? formatDate(user.createdAt) : "-"}</td>
                    <td>
                      <button
                        type="button"
                        className={cn("admin-btn", user.banned ? "admin-btn--neutral" : "admin-btn--danger")}
                        disabled={busyUserId === user.id}
                        onClick={() => void handleBanToggle(user)}
                      >
                        {user.banned ? "解封" : "封禁"}
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        <div className="admin-pagination">
          <button type="button" disabled={page <= 1 || loading} onClick={() => setPage((p) => p - 1)}>
            上一页
          </button>
          <span>
            第 {page} / {maxPage} 页
          </span>
          <button
            type="button"
            disabled={(!data.hasMore && page >= maxPage) || loading}
            onClick={() => setPage((p) => p + 1)}
          >
            下一页
          </button>
        </div>
      </section>
    </div>
  );
}
