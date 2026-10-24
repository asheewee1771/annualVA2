/** The step a dashboard is read for: the Cyber Manager's review or the VA POC's endorsement. */
export type DashboardStep = "review" | "endorse";

/**
 * The flags a System carries on the Cyber Manager's inbox and the Quarter board: Overdue, and where segregation of duties
 * refuses the caller the step the dashboard is for, because they acted at, or are assigned to, another step of its VA
 * Submission.
 */
export function ReviewFlags({
  overdue,
  sodConflict,
  step = "review",
}: {
  overdue: boolean;
  sodConflict: boolean;
  step?: DashboardStep;
}) {
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
            You acted at, or are assigned to, another step: you cannot {step} it
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
