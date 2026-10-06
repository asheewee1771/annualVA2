import type { RegisterSystem } from "../../api/systemRegister.ts";

/**
 * The System Register as it stands (stories 2 and 12): every System with its SMO/Tech Lead, Cyber Manager and Quarter.
 * A retired System stays listed and says so; an SMO/Tech Lead no sign-in has matched yet carries the Unlinked SMO flag.
 * Everything here is rendered as text.
 */
export function RegisterTable({ systems }: { systems: RegisterSystem[] }) {
  if (systems.length === 0) {
    return <p className="muted">The Register is empty. Upload the filled-in template to add the first Systems.</p>;
  }
  return (
    <div className="table-scroll">
      <table className="data-table">
        <caption className="visually-hidden">Systems in the Register</caption>
        <thead>
          <tr>
            <th scope="col">System ID</th>
            <th scope="col">System Name</th>
            <th scope="col">SMO/Tech Lead</th>
            <th scope="col">Cyber Manager</th>
            <th scope="col">Quarter</th>
            <th scope="col">Status</th>
          </tr>
        </thead>
        <tbody>
          {systems.map((system) => (
            <tr key={system.id}>
              <td className="mono">{system.systemId}</td>
              <td>{system.name}</td>
              <td>
                {system.smoEmail}
                {system.unlinkedSmo ? (
                  <>
                    {" "}
                    <span className="flag flag--warning">Unlinked SMO</span>
                  </>
                ) : null}
              </td>
              <td>{system.cyberManagerEmail}</td>
              <td>{system.quarter}</td>
              <td>{system.retired ? "Retired" : "Active"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
