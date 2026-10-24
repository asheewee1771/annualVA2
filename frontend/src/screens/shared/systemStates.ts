import type { SystemState } from "../../api/dashboards.ts";

/** How each state is named on screen (CONTEXT.md). */
export const STATE_LABELS: Readonly<Record<SystemState, string>> = {
  NOT_STARTED: "Not Started",
  DRAFT: "Draft",
  IN_REVIEW: "In Review",
  RETURNED_FOR_REWORK: "Returned for Rework",
  AWAITING_ENDORSEMENT: "Awaiting Endorsement",
  ENDORSED: "Endorsed",
  EXEMPTION_REQUESTED: "Exemption Requested",
  EXEMPTED: "Exempted",
  WITHDRAWN: "Withdrawn",
  NOT_ENDORSED: "Not Endorsed",
};
