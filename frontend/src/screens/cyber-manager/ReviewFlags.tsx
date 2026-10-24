/** The flags a System carries for its Cyber Manager: Overdue, and where segregation of duties will refuse their review. */
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
          <span className="flag flag--warning">You acted at another step: you cannot review it</span>
        </>
      ) : null}
    </>
  );
}

/** How far the review of the Findings is: confirmed, and awaiting the Cyber Manager. */
export function findingsLabel({ confirmed, awaitingReview }: { confirmed: number; awaitingReview: number }): string {
  return `${confirmed} confirmed, ${awaitingReview} awaiting review`;
}
