# A Finding changed since its last review is one whose latest review is not of its current proposal

Status: accepted (item 2 provisional, awaiting a human decision)

Ticket 32 (story 56) lets the Cyber Manager re-review only the Findings that changed when a VA Submission comes back. It adds one filter to the Findings list, one filter on the review screen, no migration, no endpoint and no module. Everything else the ticket asks for already holds by the rules of ADR 0035 and ADR 0039, and Seam 1 tests now prove it for a resubmission.

1. **What stays confirmed needs nothing new.** A review is an append-only row naming the proposal it read, and `finding.review_state` goes back to `PENDING` only when a new proposal is made (ADR 0035 item 4, ADR 0027 item 2). A return changes no review (ADR 0039 item 2), and a resubmission changes no review either. So after a resubmission, a Finding whose current proposal is the one the Cyber Manager confirmed is still `CONFIRMED`, and only a Finding proposed again since is `PENDING`. A not-applicable justification works the same way through its version (ADR 0035 item 4). The pass to endorsement keeps its one rule, that everything must be confirmed (ADR 0039 item 4). After a resubmission, that means only the changed Findings still need confirming. No second rule and no snapshot table was added.

2. **What "changed since last review" means** (provisional). A Finding has changed since its last review when it has a review and its latest review (`finding_review`, the highest `seq`) is not of its current proposal. That happens when the SMO/Tech Lead proposed again since, or a rescan took its proposal away. The latest review is enough: a review of the current proposal is always later than any review of an earlier one. The rule reads the Finding's own review rows, so it is the same after a Cyber Manager's return, after a VA POC's return (ticket 35) and after any number of rounds. A Finding reviewed in a batch (ADR 0036) counts like any other, because a batch review writes an ordinary `finding_review` per member.

   Two kinds of Finding are not "changed since last review": one never reviewed and one whose latest review is of its current proposal. A Finding never reviewed includes one entered by hand or first reported by a rescan during rework, and one left `PENDING` at a return. It is awaiting its first review and is listed by `reviewState=PENDING`. A Finding whose latest review is of its current proposal includes a Disputed Finding the SMO/Tech Lead left as it was, which stays `DISPUTED` and still stops the pass.

   The alternative is to also count a Finding with no review whose current proposal was made after the VA Submission's latest return, that is, one that is new since the last review. That widens the filter to "changed or new". It needs a reading of `submission_return` in `finding` and a decision on what the anchor is before the first return. The narrower reading was chosen because it is what the ticket names (`PENDING` only on changed ones), and the Finish the review panel already counts every Finding awaiting review.

3. **A new proposal is a change, whatever it says.** A proposal that repeats the status and wording of the one confirmed is still a new proposal row, so the Finding is `PENDING` and listed as changed (ADR 0027 item 2). Comparing the fields would make "unchanged" depend on text equality, and on evidence files that may have been replaced. It would also let a proposal go unreviewed.

4. **The filter.** `GET /api/submissions/{id}/findings` takes `changedSinceLastReview`:
   - `true` gives the Findings changed since their last review.
   - `false` gives the others.
   - Leaving it out gives every Finding.
   - Any other value is `400`, as for `batched`.

   It combines with every other filter, and the total and the pages are of the narrowed list. The list's count now joins the latest review as the page already did, using the index V21 added (`finding_review (finding_id, seq)`). The read is audited as before, with the count and no content. The rule is written once, in `FindingStore.FILTERED`. The list's `latestReview.proposalId` against `currentProposal.id` (ADR 0035 item 4) shows the same fact on each row.

5. **The screen.** The review screen has two filter chips, All Findings and Changed since last review. All Findings is the screen as before: the batches, then the Findings proposed one by one. Changed since last review lists every Finding changed since its last review in one table, whether batched or not, the most severe first. Each Finding there has its own Confirm and Dispute. The chips do not touch the not-applicable Scan Reports above them, which show their own review state, or the Finish the review panel below them.

Not done here, on purpose:
- The VA POC's return and its resubmission (ticket 35): they reuse this filter unchanged.
- The hand-over to a new Cyber Manager (story 15): the new Cyber Manager re-reviews what is not confirmed, which is `reviewState=PENDING` and `DISPUTED`, not this filter.
- A count of changed Findings on the inbox or the readiness read.

Consequences:
- A Cyber Manager who uses only Changed since last review after a resubmission does not see Findings never reviewed. The Finish the review panel's `findings-awaiting-review` count still names them, and the pass refuses until they are confirmed.
- A Finding the SMO/Tech Lead proposes again with the same status leaves CONFIRMED and needs another review.

Amends ADR 0035 item 4 (the Findings list filters by whether the latest review is of the current proposal) and ADR 0036 item 6 (the review screen gains a flat view of the changed Findings beside the batch grouping).
