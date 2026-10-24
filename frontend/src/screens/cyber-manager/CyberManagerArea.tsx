import { NO_FILTERS } from "../../api/audit.ts";
import { AppLink } from "../../app/AppLink.tsx";
import type { AreaProps } from "../shared/AreaProps.ts";
import { AuditLogScreen } from "../shared/AuditLogScreen.tsx";
import { QuarterBoardScreen, type BoardFor } from "../shared/QuarterBoardScreen.tsx";
import { CmInboxScreen } from "./CmInboxScreen.tsx";
import {
  auditedSubmissionOf,
  CM_AUDIT_PATH,
  CM_BOARD_PATH,
  CM_INBOX_PATH,
  reviewedSubmissionOf,
  reviewPath,
} from "./paths.ts";
import { ReviewScreen } from "./ReviewScreen.tsx";

/** The Cyber Manager reads the board for their review, and a started System's card opens its review screen. */
const REVIEW_BOARD: BoardFor = { step: "review", linkOf: reviewPath };

const HISTORY_INTRO =
  "What happened to the VA Submissions you reviewed, on Systems still assigned to you, newest first. Findings appear " +
  "by id only. Entries cannot be changed or deleted.";

/** The audit history (story 85): of every VA Submission the Cyber Manager reviewed, or of one, with the way back to it. */
function AuditHistory({ submissionId, classification }: { submissionId: string | undefined; classification: string }) {
  if (!submissionId) {
    return <AuditLogScreen classification={classification} title="Audit history" intro={HISTORY_INTRO} />;
  }
  return (
    <>
      <p>
        <AppLink path={reviewPath(submissionId)}>Back to the VA Submission</AppLink>
      </p>
      <AuditLogScreen
        classification={classification}
        title="Audit history of this VA Submission"
        intro={`${HISTORY_INTRO} A VA Submission's history opens to you once you have confirmed or Disputed something in it.`}
        initial={{ ...NO_FILTERS, submission: submissionId }}
      />
    </>
  );
}

/**
 * The Cyber Manager's working area (stories 48 to 50, 58 and 85): three tabs, the action inbox, the Quarter board and the
 * audit history of what they reviewed, and, beneath the inbox, the screen where a VA Submission is reviewed and its own
 * audit history.
 */
export function CyberManagerArea({ path, classification }: AreaProps) {
  const reviewing = reviewedSubmissionOf(path);
  const audited = auditedSubmissionOf(path);
  const onBoard = path === CM_BOARD_PATH;
  const onHistory = path === CM_AUDIT_PATH || audited !== undefined;
  return (
    <>
      <nav className="tabs" aria-label="Cyber Manager views">
        <ul>
          <li>
            <AppLink path={CM_INBOX_PATH} current={!onBoard && !onHistory}>
              Inbox
            </AppLink>
          </li>
          <li>
            <AppLink path={CM_BOARD_PATH} current={onBoard}>
              Quarter board
            </AppLink>
          </li>
          <li>
            <AppLink path={CM_AUDIT_PATH} current={onHistory}>
              Audit history
            </AppLink>
          </li>
        </ul>
      </nav>
      {reviewing ? (
        <ReviewScreen key={reviewing} submissionId={reviewing} classification={classification} />
      ) : onHistory ? (
        <AuditHistory key={path} submissionId={audited} classification={classification} />
      ) : onBoard ? (
        <QuarterBoardScreen classification={classification} whose="your Systems" boardFor={REVIEW_BOARD} />
      ) : (
        <CmInboxScreen classification={classification} />
      )}
    </>
  );
}
