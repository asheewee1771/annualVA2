import { createContext, useContext } from "react";

/**
 * What every part of the review screen shares: the VA Submission, whether it takes reviews now, the classification label
 * of the comment boxes, and the one refresh. `reviewed` is called after every review (and every refusal that means the
 * item changed), and `refreshKey` then changes, so every read on the screen is made again: a batch review changes its
 * members, and a member reviewed alone changes its batch's counts.
 */
export interface ReviewSession {
  submissionId: string;
  reviewable: boolean;
  classification: string;
  refreshKey: number;
  reviewed: () => void;
}

export const ReviewSessionContext = createContext<ReviewSession | undefined>(undefined);

/** The review screen's shared session; only its own parts read it. */
export function useReviewSession(): ReviewSession {
  const session = useContext(ReviewSessionContext);
  if (session === undefined) throw new Error("useReviewSession is used outside the review screen");
  return session;
}
