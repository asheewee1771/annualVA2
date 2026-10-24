import type { ReactNode } from "react";
import { AppLink } from "../../app/AppLink.tsx";
import type { AreaProps } from "../shared/AreaProps.ts";
import { AuditLogScreen } from "../shared/AuditLogScreen.tsx";
import { QuarterBoardScreen, type BoardFor } from "../shared/QuarterBoardScreen.tsx";
import { CycleMatrixScreen } from "./CycleMatrixScreen.tsx";
import { EndorseScreen } from "./EndorseScreen.tsx";
import { ExemptionQueueScreen } from "./ExemptionQueueScreen.tsx";
import { endorsedSubmissionOf, endorsePath, VA_POC_PATH } from "./paths.ts";
import { RegisterScreen } from "./RegisterScreen.tsx";

/** A VA POC reads the board for the endorsement, and a started System's card opens its VA Submission. */
const ENDORSE_BOARD: BoardFor = { step: "endorse", linkOf: endorsePath };

const MATRIX_PATH = "/va-poc/cycle-matrix";
/** The same path the server's notifications link to (Links.EXEMPTION_QUEUE). */
const EXEMPTIONS_PATH = "/va-poc/exemptions";
const AUDIT_LOG_PATH = "/va-poc/audit-log";
const REGISTER_PATH = "/va-poc/register";

/** A page beneath the area, with the way back. */
function SubPage({ children }: { children: ReactNode }) {
  return (
    <>
      <p>
        <AppLink path={VA_POC_PATH}>Back to the VA POC area</AppLink>
      </p>
      {children}
    </>
  );
}

/**
 * The VA POC's working area (stories 45, 64 and 65): three tabs, the Quarter board of every System, the Cycle matrix and
 * the Exemption requests waiting on a decision, and,
 * beneath the area, a VA Submission opened from the board (to endorse it), the System Register and the audit log.
 */
export function VaPocArea({ path, classification }: AreaProps) {
  const endorsing = endorsedSubmissionOf(path);
  if (endorsing) {
    return <EndorseScreen key={endorsing} submissionId={endorsing} />;
  }
  if (path === REGISTER_PATH) {
    return (
      <SubPage>
        <RegisterScreen classification={classification} />
      </SubPage>
    );
  }
  if (path === AUDIT_LOG_PATH) {
    return (
      <SubPage>
        <AuditLogScreen classification={classification} />
      </SubPage>
    );
  }
  const onMatrix = path === MATRIX_PATH;
  const onExemptions = path === EXEMPTIONS_PATH;
  return (
    <>
      <nav className="tabs" aria-label="VA POC views">
        <ul>
          <li>
            <AppLink path={VA_POC_PATH} current={!onMatrix && !onExemptions}>
              Quarter board
            </AppLink>
          </li>
          <li>
            <AppLink path={MATRIX_PATH} current={onMatrix}>
              Cycle matrix
            </AppLink>
          </li>
          <li>
            <AppLink path={EXEMPTIONS_PATH} current={onExemptions}>
              Exemption requests
            </AppLink>
          </li>
          <li>
            <AppLink path={REGISTER_PATH}>System Register</AppLink>
          </li>
          <li>
            <AppLink path={AUDIT_LOG_PATH}>Audit log</AppLink>
          </li>
        </ul>
      </nav>
      {onExemptions ? <ExemptionQueueScreen /> : null}
      {onMatrix ? <CycleMatrixScreen /> : null}
      {onMatrix || onExemptions ? null : (
        <QuarterBoardScreen classification={classification} whose="every System" boardFor={ENDORSE_BOARD} />
      )}
    </>
  );
}
