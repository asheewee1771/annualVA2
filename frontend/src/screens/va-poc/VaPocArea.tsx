import type { ReactNode } from "react";
import { AppLink } from "../../app/AppLink.tsx";
import type { AreaProps } from "../shared/AreaProps.ts";
import { AuditLogScreen } from "../shared/AuditLogScreen.tsx";
import { QuarterBoardScreen, type BoardFor } from "../shared/QuarterBoardScreen.tsx";
import { CycleMatrixScreen } from "./CycleMatrixScreen.tsx";
import { EndorseScreen } from "./EndorseScreen.tsx";
import { ExportsScreen } from "./ExportsScreen.tsx";
import { endorsedSubmissionOf, endorsePath, VA_POC_PATH } from "./paths.ts";
import { RegisterScreen } from "./RegisterScreen.tsx";

/** A VA POC reads the board for the endorsement, and a started System's card opens its VA Submission. */
const ENDORSE_BOARD: BoardFor = { step: "endorse", linkOf: endorsePath };

const MATRIX_PATH = "/va-poc/cycle-matrix";
const AUDIT_LOG_PATH = "/va-poc/audit-log";
const REGISTER_PATH = "/va-poc/register";
const EXPORTS_PATH = "/va-poc/exports";

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
 * The VA POC's working area (stories 64 and 65): two tabs, the Quarter board of every System and the Cycle matrix, and,
 * beneath the area, a VA Submission opened from the board (to endorse it), the System Register, the exports and the audit
 * log.
 */
export function VaPocArea({ path, classification }: AreaProps) {
  const endorsing = endorsedSubmissionOf(path);
  if (endorsing) {
    return <EndorseScreen key={endorsing} submissionId={endorsing} classification={classification} />;
  }
  if (path === REGISTER_PATH) {
    return (
      <SubPage>
        <RegisterScreen classification={classification} />
      </SubPage>
    );
  }
  if (path === EXPORTS_PATH) {
    return (
      <SubPage>
        <ExportsScreen classification={classification} />
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
  return (
    <>
      <nav className="tabs" aria-label="VA POC views">
        <ul>
          <li>
            <AppLink path={VA_POC_PATH} current={!onMatrix}>
              Quarter board
            </AppLink>
          </li>
          <li>
            <AppLink path={MATRIX_PATH} current={onMatrix}>
              Cycle matrix
            </AppLink>
          </li>
          <li>
            <AppLink path={REGISTER_PATH}>System Register</AppLink>
          </li>
          <li>
            <AppLink path={EXPORTS_PATH}>Exports</AppLink>
          </li>
          <li>
            <AppLink path={AUDIT_LOG_PATH}>Audit log</AppLink>
          </li>
        </ul>
      </nav>
      {onMatrix ? (
        <CycleMatrixScreen />
      ) : (
        <QuarterBoardScreen classification={classification} whose="every System" boardFor={ENDORSE_BOARD} />
      )}
    </>
  );
}
