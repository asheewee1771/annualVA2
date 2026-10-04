import type { ReactNode } from "react";
import { ClassificationBanner } from "../components/ClassificationBanner.tsx";
import type { Session } from "../api/session.ts";

/** The frame around every screen: skip link, classification banner, header, then the main landmark. */
export function Shell({ session, children }: { session: Session | undefined; children: ReactNode }) {
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <ClassificationBanner text={session?.classification ?? "RESTRICTED / SENSITIVE-NORMAL"} />
      <header className="shell-header">
        <h1>Annual VA</h1>
        {session?.authenticated ? <span className="muted">{session.email ?? session.name ?? "Signed in"}</span> : null}
      </header>
      {children}
    </>
  );
}
