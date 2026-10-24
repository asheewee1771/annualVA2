import type { HandledOutsideSystem } from "../../api/dashboards.ts";

/** The Systems in Quarters the manual process still runs: named only, with nothing to do in the app. */
export function HandledOutsideSection({ id, systems }: { id: string; systems: HandledOutsideSystem[] }) {
  if (systems.length === 0) return null;
  return (
    <section aria-labelledby={id}>
      <h3 id={id}>Handled outside the app ({systems.length})</h3>
      <p className="muted">These Quarters are run by the manual process. There is nothing to do here.</p>
      <div className="table-scroll">
        <table className="data-table">
          <caption className="visually-hidden">Handled outside the app</caption>
          <thead>
            <tr>
              <th scope="col">System</th>
              <th scope="col">Quarter</th>
            </tr>
          </thead>
          <tbody>
            {systems.map((system) => (
              <tr key={system.id}>
                <td>
                  <span className="mono">{system.systemId}</span> {system.name}
                </td>
                <td>Q{system.quarter}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
