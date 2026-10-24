import type { ReactNode } from "react";
import { ClassificationBanner } from "../components/ClassificationBanner.tsx";
import type { Session } from "../api/session.ts";
import { CycleChoiceProvider } from "./CycleChoice.tsx";
import { CycleSelector } from "./CycleSelector.tsx";
import { CycleStatus } from "./CycleStatus.tsx";
import { SignOutButton } from "./SignOutButton.tsx";

interface ShellProps {
  session: Session | undefined;
  /** Called after the server ended the session; present wherever a signed-in header can show. */
  onSignedOut?: () => void;
  children: ReactNode;
}

/** The session of someone signed in who holds a role: the calendar and the Cycle selector are theirs. */
function roleHolderOf(session: Session | undefined): Session | undefined {
  return session?.authenticated && (session.roles?.length ?? 0) > 0 ? session : undefined;
}

/** Who is signed in, and signing out. */
function SessionBlock({ session, onSignedOut }: { session: Session; onSignedOut: (() => void) | undefined }) {
  return (
    <div className="shell-header__session">
      <span className="muted">{session.name ?? session.email ?? "Signed in"}</span>
      {onSignedOut ? <SignOutButton onSignedOut={onSignedOut} /> : null}
    </div>
  );
}

/**
 * The frame around every screen: skip link, classification banner, header (with the VA Cycle position, and the Cycle
 * selector while two Cycles are open, for anyone who holds a role), then the main landmark. The selector's choice
 * filters every dashboard beneath it.
 */
export function Shell({ session, onSignedOut, children }: ShellProps) {
  const roleHolder = roleHolderOf(session);
  const frame = (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <ClassificationBanner text={session?.classification ?? "RESTRICTED / SENSITIVE-NORMAL"} />
      <header className="shell-header">
        <h1>Annual VA</h1>
        {roleHolder ? <CycleStatus /> : null}
        {roleHolder ? <CycleSelector classification={roleHolder.classification} /> : null}
        {session?.authenticated ? <SessionBlock session={session} onSignedOut={onSignedOut} /> : null}
      </header>
      {children}
    </>
  );
  return roleHolder ? <CycleChoiceProvider>{frame}</CycleChoiceProvider> : frame;
}
