"use client";

import ProfileEditForm from "@/components/site/ProfileEditForm";

export default function ProfileEditPage() {
  return (
    <main className="settings-page" data-testid="profile-edit-page">
      <section className="settings-shell">
        <div className="settings-header">
          <p className="settings-eyebrow">Profile</p>
          <h1>编辑资料</h1>
          <p>把你愿意留下的细节放在这里就好。</p>
        </div>
        <ProfileEditForm />
      </section>
    </main>
  );
}
