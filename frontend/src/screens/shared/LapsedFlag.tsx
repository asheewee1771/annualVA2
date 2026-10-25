/**
 * The flag a System carries on every dashboard once a conditional Exemption of it lapsed (story 47): its scheduled VA was
 * not recorded in time, so it went back where it was before the request, or became Not Endorsed after the VA Cycle closed.
 */
export function LapsedFlag({ lapsed }: { lapsed: boolean }) {
  if (!lapsed) return null;
  return (
    <>
      {" "}
      <span className="flag flag--danger">Exemption lapsed</span>
    </>
  );
}
