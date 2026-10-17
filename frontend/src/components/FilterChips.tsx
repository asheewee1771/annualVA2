/** One chip: the key it is known by and the words on it. */
export interface Chip<K extends string> {
  key: K;
  label: string;
}

/**
 * A row of filter chips, exactly one of which is on. Each is a toggle button (`aria-pressed`), so a screen reader says which
 * one is on; the screen narrows its list on the server by the chip chosen.
 */
export function FilterChips<K extends string>({
  label,
  chips,
  selected,
  onSelect,
}: {
  /** What the chips narrow, for the group's accessible name. */
  label: string;
  chips: readonly Chip<K>[];
  selected: K;
  onSelect: (key: K) => void;
}) {
  return (
    <div className="chips" role="group" aria-label={label}>
      {chips.map((chip) => (
        <button
          key={chip.key}
          type="button"
          className={chip.key === selected ? "chip chip--on" : "chip"}
          aria-pressed={chip.key === selected}
          onClick={() => onSelect(chip.key)}
        >
          {chip.label}
        </button>
      ))}
    </div>
  );
}
