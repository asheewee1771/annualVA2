import type { ReactNode } from "react";
import { AppLink } from "../../app/AppLink.tsx";
import type { AreaProps } from "../shared/AreaProps.ts";
import { AuditLogScreen } from "../shared/AuditLogScreen.tsx";
import { QuarterBoardScreen, type BoardFor } from "../shared/QuarterBoardScreen.tsx";
import { CycleMatrixScreen } from "./CycleMatrixScreen.tsx";
import { DisposalScreen } from "./DisposalScreen.tsx";
import { EndorseScreen } from "./EndorseScreen.tsx";
import { ExemptionQueueScreen } from "./ExemptionQueueScreen.tsx";
import { ExportsScreen } from "./ExportsScreen.tsx";
import { endorsedSubmissionOf, endorsePath, VA_POC_PATH } from "./paths.ts";
import { RegisterScreen } from "./RegisterScreen.tsx";

/** A VA POC reads the board for the endorsement, and a started System's card opens its VA Submission. */
const ENDORSE_BOARD: BoardFor = { step: "endorse", linkOf: endorsePath };

const MATRIX_PATH = "/va-poc/cycle-matrix";
/** The same path the server's notifications link to (Links.EXEMPTION_QUEUE). */
const EXEMPTIONS_PATH = "/va-poc/exemptions";
const AUDIT_LOG_PATH = "/va-poc/audit-log";
const REGISTER_PATH = "/va-poc/register";
const EXPORTS_PATH = "/va-poc/exports";
const DISPOSAL_PATH = "/va-poc/disposal";

/** A tab of the area: where it is, its name, and what it shows. */
interface Tab {
  path: string;
  label: string;
  render: (classification: string) => ReactNode;
}

const QUARTER_BOARD: Tab = {
  path: VA_POC_PATH,
  label: "Quarter board",
  render: (classification) => (
    <QuarterBoardScreen classification={classification} whose="every System" boardFor={ENDORSE_BOARD} />
  ),
};

const TABS: readonly Tab[] = [
  QUARTER_BOARD,
  { path: MATRIX_PATH, label: "Cycle matrix", render: () => <CycleMatrixScreen /> },
  { path: EXEMPTIONS_PATH, label: "Exemption requests", render: () => <ExemptionQueueScreen /> },
];

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
 * the Exemption requests waiting on a decision, and, beneath the area, a VA Submission opened from the board (to
 * endorse it), the System Register, the exports and the audit log.
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
  if (path === DISPOSAL_PATH) {
    return (
      <SubPage>
        <DisposalScreen />
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
  const tab = TABS.find((candidate) => candidate.path === path) ?? QUARTER_BOARD;
  return (
    <>
      <nav className="tabs" aria-label="VA POC views">
        <ul>
          {TABS.map((candidate) => (
            <li key={candidate.path}>
              <AppLink path={candidate.path} current={candidate === tab}>
                {candidate.label}
              </AppLink>
            </li>
          ))}
          <li>
            <AppLink path={REGISTER_PATH}>System Register</AppLink>
          </li>
          <li>
            <AppLink path={EXPORTS_PATH}>Exports</AppLink>
          </li>
          <li>
            <AppLink path={DISPOSAL_PATH}>Disposal</AppLink>
          </li>
          <li>
            <AppLink path={AUDIT_LOG_PATH}>Audit log</AppLink>
          </li>
        </ul>
      </nav>
      {tab.render(classification)}
    </>
  );
}
