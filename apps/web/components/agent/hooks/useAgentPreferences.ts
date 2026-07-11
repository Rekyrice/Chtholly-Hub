"use client";

import { useCallback, useEffect, useState } from "react";
import {
  loadRichMarkdownPreference,
  loadShowStepsPreference,
  loadWorkspaceDarkPreference,
  saveRichMarkdownPreference,
  saveShowStepsPreference,
  saveWorkspaceDarkPreference,
} from "@/lib/agent/sessions";

type BooleanStateAction = boolean | ((previous: boolean) => boolean);

export function useAgentPreferences() {
  const [showSteps, setShowStepsState] = useState(false);
  const [workspaceDark, setWorkspaceDarkState] = useState(false);
  const [richMarkdown, setRichMarkdownState] = useState(true);

  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- preferences are hydrated from localStorage */
    setShowStepsState(loadShowStepsPreference());
    setWorkspaceDarkState(loadWorkspaceDarkPreference());
    setRichMarkdownState(loadRichMarkdownPreference());
    /* eslint-enable react-hooks/set-state-in-effect */
  }, []);

  const setShowSteps = useCallback((value: BooleanStateAction) => {
    setShowStepsState((previous) => {
      const next = typeof value === "function" ? value(previous) : value;
      saveShowStepsPreference(next);
      return next;
    });
  }, []);

  const setWorkspaceDark = useCallback((value: BooleanStateAction) => {
    setWorkspaceDarkState((previous) => {
      const next = typeof value === "function" ? value(previous) : value;
      saveWorkspaceDarkPreference(next);
      return next;
    });
  }, []);

  const setRichMarkdown = useCallback((value: BooleanStateAction) => {
    setRichMarkdownState((previous) => {
      const next = typeof value === "function" ? value(previous) : value;
      saveRichMarkdownPreference(next);
      return next;
    });
  }, []);

  return {
    showSteps,
    setShowSteps,
    workspaceDark,
    setWorkspaceDark,
    richMarkdown,
    setRichMarkdown,
  } as const;
}
