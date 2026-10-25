import { dayLabel } from "../../api/cycles.ts";

/**
 * The SMO Unreachable flag (story 13): the mail relay refused the System's SMO/Tech Lead's address on this day, so a VA
 * POC should correct it in the System Register. The server sends the day to a VA POC only; nothing is shown without it.
 */
export function SmoUnreachableFlag({ since }: { since: string | null }) {
  if (!since) return null;
  return (
    <>
      {" "}
      <span className="flag flag--warning">SMO Unreachable since {dayLabel(since)}</span>
    </>
  );
}
