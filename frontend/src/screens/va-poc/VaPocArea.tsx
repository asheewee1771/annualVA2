import type { ReactNode } from "react";
import { AppLink } from "../../app/AppLink.tsx";
import type { AreaProps } from "../shared/AreaProps.ts";
import { AreaLanding } from "../shared/AreaLanding.tsx";
import { AuditLogScreen } from "../shared/AuditLogScreen.tsx";
import { RegisterScreen } from "./RegisterScreen.tsx";

const AREA_PATH = "/va-poc";
const AUDIT_LOG_PATH = "/va-poc/audit-log";
const REGISTER_PATH = "/va-poc/register";

/** A page beneath the area, with the way back. */
function SubPage({ children }: { children: ReactNode }) {
  return (
    <>
      <p>
        <AppLink path={AREA_PATH}>Back to the VA POC area</AppLink>
      </p>
      {children}
    </>
  );
}

/** The VA POC's working area: its landing page and, beneath it, the System Register and the audit log. */
export function VaPocArea({ path, classification }: AreaProps) {
  if (path === REGISTER_PATH) {
    return (
      <SubPage>
        <RegisterScreen />
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
  return (
    <>
      <AreaLanding title="VA POC">The Quarter board and the Cycle matrix appear here.</AreaLanding>
      <p>
        <AppLink path={REGISTER_PATH}>System Register</AppLink>
      </p>
      <p>
        <AppLink path={AUDIT_LOG_PATH}>Audit log</AppLink>
      </p>
    </>
  );
}
