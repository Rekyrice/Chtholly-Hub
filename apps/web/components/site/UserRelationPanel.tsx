"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import FollowButton from "@/components/site/FollowButton";
import FollowListModal from "@/components/site/FollowListModal";
import { getStoredAuth } from "@/lib/auth/tokens";
import type { RelationStatus, UserCounter, UserId } from "@/lib/types/relation";

type FollowListTab = "following" | "followers";

type UserRelationPanelProps = {
  userId: UserId;
  initialCounter?: UserCounter;
};

export default function UserRelationPanel({
  userId,
  initialCounter,
}: UserRelationPanelProps) {
  const [counter, setCounter] = useState<UserCounter | undefined>(initialCounter);
  const [isSelf, setIsSelf] = useState(false);
  const [modalTab, setModalTab] = useState<FollowListTab>("followers");
  const [modalOpen, setModalOpen] = useState(false);

  useEffect(() => {
    const sync = () => {
      const auth = getStoredAuth();
      setIsSelf(String(auth?.user?.id ?? "") === String(userId));
    };
    sync();
    window.addEventListener("chtholly-auth-change", sync);
    return () => window.removeEventListener("chtholly-auth-change", sync);
  }, [userId]);

  const handleStatusChange = (_status: RelationStatus, nextCounter?: UserCounter) => {
    if (nextCounter) setCounter(nextCounter);
  };

  const openModal = (tab: FollowListTab) => {
    setModalTab(tab);
    setModalOpen(true);
  };

  return (
    <div className="user-relation-panel">
      <div className="user-relation-stats" aria-label="用户关系数据">
        <button type="button" onClick={() => openModal("followers")}>
          <strong>{counter?.followers ?? 0}</strong>
          <span>粉丝</span>
        </button>
        <button type="button" onClick={() => openModal("following")}>
          <strong>{counter?.followings ?? 0}</strong>
          <span>关注</span>
        </button>
      </div>

      {isSelf ? (
        <Link href="/profile/edit" className="user-edit-profile-btn">
          编辑资料
        </Link>
      ) : (
        <FollowButton
          userId={userId}
          initialCounter={counter}
          onStatusChange={handleStatusChange}
          className="user-relation-follow"
        />
      )}

      <FollowListModal
        userId={userId}
        open={modalOpen}
        initialTab={modalTab}
        onClose={() => setModalOpen(false)}
      />
    </div>
  );
}
