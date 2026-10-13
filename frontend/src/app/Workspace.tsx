import { useEffect } from "react";
import type { Session } from "../api/session.ts";
import { AuditorLanding } from "../screens/auditor/Landing.tsx";
import { CyberManagerLanding } from "../screens/cyber-manager/Landing.tsx";
import { SmoArea } from "../screens/smo/SmoArea.tsx";
import type { AreaProps } from "../screens/shared/AreaProps.ts";
import { VaPocArea } from "../screens/va-poc/VaPocArea.tsx";
import { RoleNav } from "./RoleNav.tsx";
import { areasFor, type Area, type Role } from "./roles.ts";
import { navigate, usePath } from "./router.ts";

const LANDINGS: Readonly<Record<Role, (props: AreaProps) => React.JSX.Element>> = {
  VA_POC: VaPocArea,
  CYBER_MANAGER: CyberManagerLanding,
  SMO: SmoArea,
  AUDITOR: AuditorLanding,
};

/** The area a path belongs to: the area's own path or anything beneath it. */
function areaOf(areas: Area[], path: string): Area | undefined {
  return areas.find((area) => path === area.path || path.startsWith(`${area.path}/`));
}

/**
 * The signed-in person's place in the app: a nav entry for each role held, and the area for the
 * current path. A path that is not one of their areas (the root, or a role they no longer hold)
 * sends them to their first role's landing area. A person with no role is told so.
 */
export function Workspace({ session }: { session: Session }) {
  const areas = areasFor(session.roles);
  const path = usePath();
  const current = areaOf(areas, path);
  const first = areas[0];

  useEffect(() => {
    if (first && !current) navigate(first.path, true);
  }, [first, current]);

  if (!first) {
    return (
      <main id="main" className="shell-main">
        <div className="card">
          <h2>No role yet</h2>
          <p className="muted">
            You are signed in, but you have no role in Annual VA. You get one when the VA POC names you in the System
            Register, or grants you access.
          </p>
        </div>
      </main>
    );
  }

  const Landing = current ? LANDINGS[current.role] : undefined;
  return (
    <>
      <RoleNav areas={areas} currentPath={current?.path} />
      <main id="main" className="shell-main">
        {Landing ? <Landing path={path} classification={session.classification} /> : null}
      </main>
    </>
  );
}
