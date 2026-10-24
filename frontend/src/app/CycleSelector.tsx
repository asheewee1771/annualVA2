import { ClassifiedField } from "../components/ClassifiedField.tsx";
import { openYears, useCycleChoice } from "./CycleChoice.tsx";

/**
 * The shell's VA Cycle selector (story 17), shown only while two Cycles are open at once (the older one still taking late
 * Q4 endorsements). Its choice filters every dashboard; it opens on the Cycle running today.
 */
export function CycleSelector({ classification }: { classification: string }) {
  const choice = useCycleChoice();
  const list = choice?.cycles.data;
  const years = openYears(list);
  if (!choice || !list || years.length < 2) return null;
  return (
    <ClassifiedField label="VA Cycle" classification={classification} id="shell-cycle">
      <select
        id="shell-cycle"
        value={choice.selected ?? list.currentCycle ?? ""}
        onChange={(event) => choice.select(Number(event.target.value))}
      >
        {years.map((year) => (
          <option key={year} value={year}>
            VA Cycle {year}
          </option>
        ))}
      </select>
    </ClassifiedField>
  );
}
