import type { BlockReason, SystemSnapshot } from "../../api/systemRegister.ts";

/** What each Blocked reason means, in the words a VA POC fixing their file needs. The server sends only the code. */
export const REASON_TEXT: Readonly<Record<BlockReason, string>> = {
  SYSTEM_ID_MISSING: "The System ID is empty.",
  SYSTEM_ID_INVALID: "The System ID is longer than 64 characters or holds characters that are not allowed.",
  SYSTEM_ID_DUPLICATE: "The System ID appears on more than one row (upper and lower case count as the same).",
  SYSTEM_ID_EXAMPLE: "The System ID starts EXAMPLE-: this is the template's sample row. Delete it before uploading.",
  SYSTEM_NAME_MISSING: "The System Name is empty.",
  SYSTEM_NAME_INVALID: "The System Name is longer than 200 characters or holds characters that are not allowed.",
  SMO_EMAIL_MISSING: "The SMO/Tech Lead email is empty.",
  SMO_EMAIL_INVALID: "The SMO/Tech Lead email is not one valid email address.",
  CYBER_MANAGER_MISSING: "There is no Cyber Manager. Every System needs an independent reviewer.",
  CYBER_MANAGER_EMAIL_INVALID: "The Cyber Manager email is not one valid email address.",
  CYBER_MANAGER_IS_SMO: "The Cyber Manager is the System's own SMO/Tech Lead. Name a different reviewer.",
  QUARTER_MISSING: "The Quarter is empty. Use Q1, Q2, Q3 or Q4.",
  QUARTER_INVALID: "The Quarter must be Q1, Q2, Q3 or Q4.",
  FILES_SCANNING:
    "A file of its VA Submission is still being checked. Wait until the check finishes, then upload the file again.",
  MOVE_IN_FLIGHT:
    "Its VA Submission is in progress. Moving it to another Quarter mid-cycle arrives with Release 2: keep its Quarter for now.",
  TRANSFER_IN_FLIGHT:
    "Its VA Submission is in progress. Changing its SMO/Tech Lead or Cyber Manager mid-cycle arrives with Release 2: keep them for now.",
  SEGREGATION_OF_DUTIES:
    "The change would give one person two steps of its VA Submission (for example its Cyber Manager becoming its SMO/Tech Lead).",
};

export interface FieldChange {
  label: string;
  from: string;
  to: string;
}

function status(snapshot: SystemSnapshot): string {
  return snapshot.retired ? "Retired" : "Active";
}

/** The fields an UPDATE changes, each as before and after; a field that stays the same is not listed. */
export function changedFields(before: SystemSnapshot, after: SystemSnapshot): FieldChange[] {
  const pairs: FieldChange[] = [
    { label: "System Name", from: before.name, to: after.name },
    { label: "SMO/Tech Lead", from: before.smoEmail, to: after.smoEmail },
    { label: "Cyber Manager", from: before.cyberManagerEmail, to: after.cyberManagerEmail },
    { label: "Quarter", from: before.quarter, to: after.quarter },
    { label: "Status", from: status(before), to: status(after) },
  ];
  return pairs.filter((pair) => pair.from !== pair.to);
}
