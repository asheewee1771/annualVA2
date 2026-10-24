import { createContext, useCallback, useContext, useState, type ReactNode } from "react";
import { fetchCycles, type CycleList } from "../api/cycles.ts";
import { useResource, type Resource } from "../hooks/useResource.ts";

/**
 * The VA Cycle every dashboard shows (story 17): the shell reads GET /api/cycles once, and while two Cycles are open it
 * offers a selector whose choice filters every dashboard call. Undefined means the server's default, the Cycle running
 * today. A choice of a Cycle that has since closed falls back to that default, so a dashboard is never left on a Cycle
 * the selector no longer offers.
 */
interface CycleChoiceValue {
  cycles: Resource<CycleList>;
  /** The chosen Cycle's year, or undefined for the Cycle running today. */
  selected: number | undefined;
  select: (year: number) => void;
}

const CycleChoiceContext = createContext<CycleChoiceValue | undefined>(undefined);

/** The years of the open Cycles, oldest first. */
export function openYears(list: CycleList | undefined): number[] {
  return list?.cycles.filter((cycle) => cycle.open).map((cycle) => cycle.year) ?? [];
}

export function CycleChoiceProvider({ children }: { children: ReactNode }) {
  const cycles = useResource(fetchCycles);
  const [chosen, setChosen] = useState<number | undefined>(undefined);
  const open = openYears(cycles.data);
  const selected = chosen !== undefined && open.includes(chosen) ? chosen : undefined;
  const select = useCallback((year: number) => setChosen(year), []);
  return <CycleChoiceContext.Provider value={{ cycles, selected, select }}>{children}</CycleChoiceContext.Provider>;
}

/** The shell's Cycle choice; outside a signed-in shell, the server's default and no list. */
export function useCycleChoice(): CycleChoiceValue | undefined {
  return useContext(CycleChoiceContext);
}

/** The year every dashboard call names: the shell's choice, or undefined for the Cycle running today. */
export function useSelectedCycle(): number | undefined {
  return useContext(CycleChoiceContext)?.selected;
}
