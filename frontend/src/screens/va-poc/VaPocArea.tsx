import { AppLink } from "../../app/AppLink.tsx";
import type { AreaProps } from "../shared/AreaProps.ts";
import { AreaLanding } from "../shared/AreaLanding.tsx";
import { AuditLogScreen } from "../shared/AuditLogScreen.tsx";

const AUDIT_LOG_PATH = "/va-poc/audit-log";

/** The VA POC's working area: its landing page and, beneath it, the audit log. */
export function VaPocArea({ path, classification }: AreaProps) {
  if (path === AUDIT_LOG_PATH) {
    return (
      <>
        <p>
          <AppLink path="/va-poc">Back to the VA POC area</AppLink>
        </p>
        <AuditLogScreen classification={classification} />
      </>
    );
  }
  return (
    <>
      <AreaLanding title="VA POC">The Quarter board and the Cycle matrix appear here.</AreaLanding>
      <p>
        <AppLink path={AUDIT_LOG_PATH}>Audit log</AppLink>
      </p>
    </>
  );
}
