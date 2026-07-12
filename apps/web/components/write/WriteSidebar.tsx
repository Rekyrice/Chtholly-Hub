import { Check, Circle, Sparkles } from "lucide-react";
import { countWritingStats } from "@/lib/utils/markdownInsert";

export type WriteSaveStatus = "saved" | "saving" | "unsaved";

type WriteSidebarProps = {
  title: string;
  tags: string[];
  description: string;
  markdown: string;
  saveStatus: WriteSaveStatus;
};

const SAVE_STATUS_LABEL: Record<WriteSaveStatus, string> = {
  saved: "已保存",
  saving: "保存中…",
  unsaved: "有未保存的更改",
};

export default function WriteSidebar({
  title,
  tags,
  description,
  markdown,
  saveStatus,
}: WriteSidebarProps) {
  const stats = countWritingStats(markdown);
  const checks = [
    { label: "标题", ready: title.trim().length > 0 },
    { label: "标签", ready: tags.length > 0 },
    { label: "摘要", ready: description.trim().length > 0 },
    { label: "正文", ready: markdown.trim().length > 0 },
  ];

  return (
    <aside className="write-sidebar" aria-label="写作辅助">
      <section className="write-sidebar__card">
        <div className="write-sidebar__heading">
          <span>发布检查</span>
          <small>草稿状态：{SAVE_STATUS_LABEL[saveStatus]}</small>
        </div>
        <ul className="write-sidebar__checklist">
          {checks.map((item) => (
            <li
              className={item.ready ? "write-sidebar__check--ready" : undefined}
              data-testid={item.ready ? "write-check-ready" : "write-check-pending"}
              key={item.label}
            >
              {item.ready ? <Check size={15} /> : <Circle size={15} />}
              <span>{item.label}</span>
            </li>
          ))}
        </ul>
      </section>

      <section className="write-sidebar__card">
        <h2>写作统计</h2>
        <div className="write-sidebar__stats">
          <strong>{stats.charCount} 字</strong>
          <span>约 {stats.readingMinutes} 分钟阅读</span>
          <span>{stats.paragraphs} 段</span>
        </div>
      </section>

      <section className="write-sidebar__card">
        <h2>Markdown 快捷写法</h2>
        <dl className="write-sidebar__syntax">
          <div><dt>##</dt><dd>二级标题</dd></div>
          <div><dt>&gt;</dt><dd>引用</dd></div>
          <div><dt>-</dt><dd>无序列表</dd></div>
          <div><dt>```</dt><dd>代码块</dd></div>
        </dl>
      </section>

      <section className="write-sidebar__card write-sidebar__companion">
        <Sparkles size={18} aria-hidden="true" />
        <div>
          <h2>珂朵莉的提示</h2>
          <p>先把真正想说的那句话写下来，结构可以稍后再整理。</p>
        </div>
      </section>
    </aside>
  );
}
