import type { ReactNode } from "react";

/** The landing card every role area starts from, until that role's dashboard arrives. */
export function AreaLanding({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="card">
      <h2>{title}</h2>
      <p className="muted">{children}</p>
    </div>
  );
}
