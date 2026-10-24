import { useCallback, useEffect, useState } from "react";
import { ApiError } from "../api/problem.ts";

export interface Resource<T> {
  data: T | undefined;
  error: ApiError | undefined;
  loading: boolean;
  reload: () => void;
}

/**
 * Loads one API resource and exposes its load state; every screen reads data through this. It loads again when `load`
 * changes, on `reload()`, and when `refreshKey` changes (a screen-wide refresh after a write elsewhere on it).
 */
export function useResource<T>(load: () => Promise<T>, refreshKey = 0): Resource<T> {
  const [data, setData] = useState<T | undefined>(undefined);
  const [error, setError] = useState<ApiError | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    load().then(
      (result) => {
        if (cancelled) return;
        setData(result);
        setError(undefined);
        setLoading(false);
      },
      (failure: unknown) => {
        if (cancelled) return;
        setError(
          failure instanceof ApiError
            ? failure
            : new ApiError({ type: "about:blank", title: "The server could not be reached", status: 0 }),
        );
        setLoading(false);
      },
    );
    return () => {
      cancelled = true;
    };
  }, [load, attempt, refreshKey]);

  const reload = useCallback(() => setAttempt((n) => n + 1), []);
  return { data, error, loading, reload };
}
