import { AppLink } from "../../app/AppLink.tsx";
import type { AreaProps } from "../shared/AreaProps.ts";
import { CmInboxScreen } from "./CmInboxScreen.tsx";
import { QuarterBoardScreen } from "./QuarterBoardScreen.tsx";

const INBOX_PATH = "/cyber-manager";
const BOARD_PATH = "/cyber-manager/quarter-board";

/** The Cyber Manager's working area (stories 48 and 49): two tabs, the action inbox and the Quarter board. */
export function CyberManagerArea({ path, classification }: AreaProps) {
  const onBoard = path === BOARD_PATH;
  return (
    <>
      <nav className="tabs" aria-label="Cyber Manager views">
        <ul>
          <li>
            <AppLink path={INBOX_PATH} current={!onBoard}>
              Inbox
            </AppLink>
          </li>
          <li>
            <AppLink path={BOARD_PATH} current={onBoard}>
              Quarter board
            </AppLink>
          </li>
        </ul>
      </nav>
      {onBoard ? (
        <QuarterBoardScreen classification={classification} />
      ) : (
        <CmInboxScreen classification={classification} />
      )}
    </>
  );
}
