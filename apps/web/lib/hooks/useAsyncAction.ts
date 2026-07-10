"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { extractErrorMessage } from "@/lib/hooks/useErrorMessage";

type AsyncActionOptions<TResult> = {
  fallbackError?: string;
  onSuccess?: (result: TResult) => void;
  onError?: (error: unknown) => void;
  rethrow?: boolean;
};

export function useAsyncAction<TArgs extends unknown[], TResult>(
  asyncFn: (...args: TArgs) => Promise<TResult>,
  options: AsyncActionOptions<TResult> = {},
) {
  const fnRef = useRef(asyncFn);
  const optionsRef = useRef(options);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fnRef.current = asyncFn;
    optionsRef.current = options;
  });

  const execute = useCallback(async (...args: TArgs): Promise<TResult | undefined> => {
    setLoading(true);
    setError(null);
    try {
      const result = await fnRef.current(...args);
      optionsRef.current.onSuccess?.(result);
      return result;
    } catch (caught) {
      setError(extractErrorMessage(caught, optionsRef.current.fallbackError));
      optionsRef.current.onError?.(caught);
      if (optionsRef.current.rethrow) {
        throw caught;
      }
      return undefined;
    } finally {
      setLoading(false);
    }
  }, []);

  const reset = useCallback(() => setError(null), []);

  return { execute, loading, error, setError, reset } as const;
}
