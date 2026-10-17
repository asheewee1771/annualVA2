import type { ChangeEvent } from "react";
import type { ReportFile } from "../../api/reportFiles.ts";
import { ClassifiedField } from "../../components/ClassifiedField.tsx";
import type { FieldName, FieldSpec } from "./findingStatuses.ts";

/**
 * One input of a status's fields, with its classification label beside it (IM8 dp-8): a long text, a one-line text, a
 * date, or the choice of an approval memo among the supporting evidence files that have finished their checks. Used by a
 * Finding's own row and by the bulk bar; `idPrefix` keeps each input's id unique on the page.
 */
export function ProposalFieldInput({
  idPrefix,
  spec,
  value,
  invalid,
  memos,
  classification,
  onChange,
}: {
  idPrefix: string;
  spec: FieldSpec;
  value: string;
  invalid: boolean;
  memos: readonly ReportFile[];
  classification: string;
  onChange: (name: FieldName, value: string) => void;
}) {
  const id = `${idPrefix}-${spec.name}`;
  const change = (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    onChange(spec.name, event.target.value);
  const common = { id, value, onChange: change, "aria-invalid": invalid, required: spec.required } as const;
  return (
    <ClassifiedField label={spec.label} classification={classification} id={id}>
      {spec.kind === "text" ? <textarea {...common} rows={3} maxLength={spec.maxLength} /> : null}
      {spec.kind === "line" ? <input {...common} type="text" maxLength={spec.maxLength} /> : null}
      {spec.kind === "date" ? <input {...common} type="date" /> : null}
      {spec.kind === "memo" ? (
        <select {...common}>
          <option value="">No approval memo</option>
          {memos.map((memo) => (
            <option key={memo.id} value={memo.id}>
              {memo.name}
            </option>
          ))}
        </select>
      ) : null}
    </ClassifiedField>
  );
}
