/** The four roles the server can grant, and the working area each one lands in. */
export type Role = "VA_POC" | "CYBER_MANAGER" | "SMO" | "AUDITOR";

export interface Area {
  role: Role;
  path: string;
  label: string;
}

const AREAS: Readonly<Record<Role, Area>> = {
  VA_POC: { role: "VA_POC", path: "/va-poc", label: "VA POC" },
  CYBER_MANAGER: { role: "CYBER_MANAGER", path: "/cyber-manager", label: "Cyber Manager" },
  SMO: { role: "SMO", path: "/smo", label: "SMO/Tech Lead" },
  AUDITOR: { role: "AUDITOR", path: "/auditor", label: "Auditor" },
};

/**
 * One area per role held, in the order the server listed them (highest privilege first), so the
 * first is where the person lands. A role this build does not know is left out. What a person may
 * do is decided by the server on every call; this only decides what the shell shows.
 */
export function areasFor(roles: readonly string[] | undefined): Area[] {
  const areas: Area[] = [];
  for (const name of roles ?? []) {
    const area = Object.hasOwn(AREAS, name) ? AREAS[name as Role] : undefined;
    if (area && !areas.includes(area)) areas.push(area);
  }
  return areas;
}
