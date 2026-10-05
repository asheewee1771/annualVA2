import type { ReactNode } from "react";
import { ClassificationBanner } from "../components/ClassificationBanner.tsx";
import type { Session } from "../api/session.ts";
import { SignOutButton } from "./SignOutButton.tsx";

interface ShellProps {
  session: Session | undefined;
  /** Called after the server ended the session; present wherever a signed-in header can show. */
  onSignedOut?: () => void;
  children: ReactNode;
}

/** The frame around every screen: skip link, classification banner, header, then the main landmark. */
export function Shell({ session, onSignedOut, children }: ShellProps) {
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <ClassificationBanner text={session?.classification ?? "RESTRICTED / SENSITIVE-NORMAL"} />
      <header className="shell-header">
        <h1>Annual VA</h1>
        {session?.authenticated ? (
          <div className="shell-header__session">
            <span className="muted">{session.name ?? session.email ?? "Signed in"}</span>
            {onSignedOut ? <SignOutButton onSignedOut={onSignedOut} /> : null}
          </div>
        ) : null}
      </header>
      {children}
    </>
  );
}
