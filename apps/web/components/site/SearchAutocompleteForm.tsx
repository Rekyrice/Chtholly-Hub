"use client";

import {
  type CompositionEvent,
  type KeyboardEvent,
  useEffect,
  useId,
  useRef,
  useState,
} from "react";
import { searchService } from "@/lib/services/searchService";
import type { SearchSort } from "@/lib/types/search";

type SearchAutocompleteFormProps = {
  initialQuery: string;
  tags: string[];
  sort: SearchSort;
};

const SUGGESTION_DELAY_MS = 250;

export default function SearchAutocompleteForm({
  initialQuery,
  tags,
  sort,
}: SearchAutocompleteFormProps) {
  const [query, setQuery] = useState(initialQuery);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [open, setOpen] = useState(false);
  const [focused, setFocused] = useState(false);
  const [loading, setLoading] = useState(false);
  const [isComposing, setIsComposing] = useState(false);
  const listboxId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const requestSequence = useRef(0);
  const mounted = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      requestSequence.current += 1;
    };
  }, []);

  useEffect(() => {
    const prefix = query.trim();
    const sequence = ++requestSequence.current;

    if (isComposing || prefix.length < 1) {
      return;
    }

    const timer = window.setTimeout(() => {
      if (!mounted.current || sequence !== requestSequence.current) return;
      setLoading(true);
      void searchService
        .suggest(prefix, 8)
        .then((response) => {
          if (!mounted.current || sequence !== requestSequence.current) return;
          const items = response.items.filter(
            (item): item is string => typeof item === "string" && item.trim().length > 0,
          );
          setSuggestions(items);
          setActiveIndex(-1);
          setOpen(focused && items.length > 0);
          setLoading(false);
        })
        .catch(() => {
          if (!mounted.current || sequence !== requestSequence.current) return;
          setSuggestions([]);
          setActiveIndex(-1);
          setOpen(false);
          setLoading(false);
        });
    }, SUGGESTION_DELAY_MS);

    return () => window.clearTimeout(timer);
  }, [focused, isComposing, query]);

  const activeOption = activeIndex >= 0 ? suggestions[activeIndex] : undefined;
  const activeOptionId = activeOption ? `${listboxId}-option-${activeIndex}` : undefined;
  const expanded = open && suggestions.length > 0;

  const selectSuggestion = (suggestion: string) => {
    requestSequence.current += 1;
    setQuery(suggestion);
    setSuggestions([]);
    setActiveIndex(-1);
    setOpen(false);
    setLoading(false);
    inputRef.current?.focus();
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if ((event.key === "ArrowDown" || event.key === "ArrowUp") && suggestions.length > 0) {
      event.preventDefault();
      setOpen(true);
      setActiveIndex((current) => {
        if (event.key === "ArrowDown") return (current + 1) % suggestions.length;
        return current <= 0 ? suggestions.length - 1 : current - 1;
      });
      return;
    }

    if (event.key === "Enter" && expanded && activeOption) {
      event.preventDefault();
      selectSuggestion(activeOption);
      return;
    }

    if (event.key === "Escape") {
      event.preventDefault();
      setOpen(false);
      setActiveIndex(-1);
    }
  };

  const handleCompositionEnd = (event: CompositionEvent<HTMLInputElement>) => {
    setQuery(event.currentTarget.value);
    setIsComposing(false);
  };

  return (
    <form action="/search" method="get" className="search-autocomplete" role="search">
      <label className="search-autocomplete__label" htmlFor={`${listboxId}-input`}>
        搜索文章
      </label>
      <div className="search-autocomplete__control">
        <input
          ref={inputRef}
          id={`${listboxId}-input`}
          type="search"
          name="q"
          value={query}
          autoComplete="off"
          placeholder="搜索标题、摘要或正文"
          className="search-autocomplete__input"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={expanded}
          aria-controls={listboxId}
          aria-activedescendant={activeOptionId}
          onChange={(event) => {
            requestSequence.current += 1;
            setQuery(event.target.value);
            setSuggestions([]);
            setActiveIndex(-1);
            setLoading(false);
            setOpen(false);
          }}
          onFocus={() => {
            setFocused(true);
            if (!isComposing && query.trim().length > 0 && suggestions.length > 0) {
              setOpen(true);
            }
          }}
          onBlur={() => {
            setFocused(false);
            setOpen(false);
          }}
          onKeyDown={handleKeyDown}
          onCompositionStart={() => {
            requestSequence.current += 1;
            setIsComposing(true);
            setSuggestions([]);
            setActiveIndex(-1);
            setLoading(false);
            setOpen(false);
          }}
          onCompositionEnd={handleCompositionEnd}
        />
        {tags.length > 0 && <input type="hidden" name="tags" value={tags.join(",")} />}
        <input type="hidden" name="sort" value={sort} />
        <button type="submit" className="search-autocomplete__submit">
          搜索
        </button>
      </div>

      {expanded && (
        <ul id={listboxId} className="search-autocomplete__list" role="listbox">
          {suggestions.map((suggestion, index) => (
            <li key={`${suggestion}-${index}`} role="presentation">
              <button
                id={`${listboxId}-option-${index}`}
                type="button"
                role="option"
                aria-selected={index === activeIndex}
                className="search-autocomplete__option"
                onMouseDown={(event) => {
                  event.preventDefault();
                  selectSuggestion(suggestion);
                }}
              >
                {suggestion}
              </button>
            </li>
          ))}
        </ul>
      )}

      {focused && query.trim() && !expanded && (
        <p className="search-autocomplete__status" aria-live="polite">
          {loading ? "正在检索建议…" : "继续输入或按回车搜索"}
        </p>
      )}
    </form>
  );
}
