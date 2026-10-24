import { fetchCycles } from "../../api/cycles.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import { useResource } from "../../hooks/useResource.ts";

/** Shown only while two VA Cycles are open at once (the older one is still taking endorsements). */
export function CycleSelector({
  id,
  classification,
  selected,
  onSelect,
}: {
  id: string;
  classification: string;
  selected: number | undefined;
  onSelect: (year: number) => void;
}) {
  const cycles = useResource(fetchCycles);
  const years = cycles.data?.cycles.filter((candidate) => candidate.phase !== "CLOSED").map((c) => c.year) ?? [];
  if (years.length < 2) return null;
  return (
    <ClassifiedField label="VA Cycle" classification={classification} id={id}>
      <select id={id} value={selected ?? ""} onChange={(event) => onSelect(Number(event.target.value))}>
        {years.map((year) => (
          <option key={year} value={year}>
            VA Cycle {year}
          </option>
        ))}
      </select>
    </ClassifiedField>
  );
}
