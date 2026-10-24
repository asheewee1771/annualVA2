/**
 * The flags a System carries on the Cyber Manager's inbox and the Quarter board: Overdue, and where segregation of duties
 * refuses the caller's review, because they acted at, or are assigned to, another step of its VA Submission (never set
 * on a VA POC's board until the endorsing step is recorded).
 */
export function ReviewFlags({ overdue, sodConflict }: { overdue: boolean; sodConflict: boolean }) {
  return (
    <>
      {overdue ? (
        <>
          {" "}
          <span className="flag flag--danger">Overdue</span>
        </>
      ) : null}
      {sodConflict ? (
        <>
          {" "}
          <span className="flag flag--warning">
            You acted at, or are assigned to, another step: you cannot review it
          </span>
        </>
      ) : null}
    </>
  );
}

/** How far the review of the Findings is: confirmed, and awaiting the Cyber Manager. */
export function findingsLabel({ confirmed, awaitingReview }: { confirmed: number; awaitingReview: number }): string {
  return `${confirmed} confirmed, ${awaitingReview} awaiting review`;
}
