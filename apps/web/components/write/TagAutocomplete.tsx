"use client";

import { KeyboardEvent, useEffect, useMemo, useRef, useState } from "react";
import { tagService } from "@/lib/services/tagService";
import type { TagItem } from "@/lib/types/tag";
import { cn } from "@/lib/utils";

type TagAutocompleteProps = {
  value: string[];
  onChange: (tags: string[]) => void;
  maxTags?: number;
};

export default function TagAutocomplete({
  value,
  onChange,
  maxTags = 20,
}: TagAutocompleteProps) {
  const [catalog, setCatalog] = useState<TagItem[]>([]);
  const [input, setInput] = useState("");
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let alive = true;
    void tagService
      .list(50)
      .then((tags) => {
        if (alive) setCatalog(tags);
      })
      .catch(() => {
        if (alive) setCatalog([]);
      });
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    const onPointerDown = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, []);

  const suggestions = useMemo(() => {
    const query = input.trim().toLowerCase();
    if (!query) return [];
    const selected = new Set(value.map((tag) => tag.toLowerCase()));
    const prefix: TagItem[] = [];
    const contains: TagItem[] = [];
    for (const tag of catalog) {
      const name = tag.name?.trim();
      if (!name || selected.has(name.toLowerCase())) continue;
      const lower = name.toLowerCase();
      if (lower.startsWith(query)) prefix.push(tag);
      else if (lower.includes(query)) contains.push(tag);
    }
    return [...prefix, ...contains].slice(0, 5);
  }, [catalog, input, value]);

  useEffect(() => {
    setActiveIndex(0);
  }, [suggestions]);

  const addTag = (raw: string) => {
    const tag = raw.trim();
    if (!tag) return;
    if (value.some((item) => item.toLowerCase() === tag.toLowerCase())) {
      setInput("");
      setOpen(false);
      return;
    }
    if (value.length >= maxTags) return;
    onChange([...value, tag]);
    setInput("");
    setOpen(false);
  };

  const removeTag = (tag: string) => {
    onChange(value.filter((item) => item !== tag));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowDown" && suggestions.length > 0) {
      event.preventDefault();
      setOpen(true);
      setActiveIndex((prev) => (prev + 1) % suggestions.length);
      return;
    }
    if (event.key === "ArrowUp" && suggestions.length > 0) {
      event.preventDefault();
      setOpen(true);
      setActiveIndex((prev) => (prev - 1 + suggestions.length) % suggestions.length);
      return;
    }
    if ((event.key === "Tab" || event.key === "Enter") && open && suggestions[activeIndex]) {
      event.preventDefault();
      addTag(suggestions[activeIndex].name);
      return;
    }
    if (event.key === "Enter" || event.key === "," || event.key === "，") {
      event.preventDefault();
      addTag(input);
      return;
    }
    if (event.key === "Backspace" && !input && value.length > 0) {
      removeTag(value[value.length - 1]);
    }
  };

  return (
    <div className="write-tag-autocomplete" ref={rootRef}>
      <div className="write-tag-box">
        {value.map((tag) => (
          <button
            type="button"
            key={tag}
            className="write-tag write-tag--selected"
            onClick={() => removeTag(tag)}
            title="点击移除"
          >
            {tag}
          </button>
        ))}
        <input
          value={input}
          onChange={(event) => {
            setInput(event.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          className="write-tag-input"
          placeholder={value.length ? "" : "标签，回车或 Tab 补全"}
          aria-label="标签"
          aria-autocomplete="list"
          aria-expanded={open && suggestions.length > 0}
        />
      </div>

      {open && suggestions.length > 0 && (
        <ul className="write-tag-suggestions" role="listbox">
          {suggestions.map((tag, index) => (
            <li key={tag.id}>
              <button
                type="button"
                role="option"
                aria-selected={index === activeIndex}
                className={cn(
                  "write-tag-suggestion",
                  index === activeIndex && "write-tag-suggestion--active",
                )}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => addTag(tag.name)}
              >
                <span>{tag.name}</span>
                <small>{tag.usageCount ?? 0} 篇</small>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
