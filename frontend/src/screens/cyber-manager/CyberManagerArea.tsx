import { AppLink } from "../../app/AppLink.tsx";
import type { AreaProps } from "../shared/AreaProps.ts";
import { CmInboxScreen } from "./CmInboxScreen.tsx";
import { CM_BOARD_PATH, CM_INBOX_PATH, reviewedSubmissionOf } from "./paths.ts";
import { QuarterBoardScreen } from "./QuarterBoardScreen.tsx";
import { ReviewScreen } from "./ReviewScreen.tsx";

/**
 * The Cyber Manager's working area (stories 48 to 50 and 58): two tabs, the action inbox and the Quarter board, and,
 * beneath the inbox, the screen where a VA Submission is reviewed.
 */
export function CyberManagerArea({ path, classification }: AreaProps) {
  const onBoard = path === CM_BOARD_PATH;
  const reviewing = reviewedSubmissionOf(path);
  return (
    <>
      <nav className="tabs" aria-label="Cyber Manager views">
        <ul>
          <li>
            <AppLink path={CM_INBOX_PATH} current={!onBoard}>
              Inbox
            </AppLink>
          </li>
          <li>
            <AppLink path={CM_BOARD_PATH} current={onBoard}>
              Quarter board
            </AppLink>
          </li>
        </ul>
      </nav>
      {reviewing ? (
        <ReviewScreen key={reviewing} submissionId={reviewing} classification={classification} />
      ) : onBoard ? (
        <QuarterBoardScreen classification={classification} />
      ) : (
        <CmInboxScreen classification={classification} />
      )}
    </>
  );
}
