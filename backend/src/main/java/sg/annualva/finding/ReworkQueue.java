package sg.annualva.finding;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of the rework queue of a VA Submission (story 60): the Findings its SMO/Tech Lead still has to act on, stage by
 * stage, and how many each stage holds. Every text in an item was read from a report or written by a person: it is data,
 * rendered only as text, and never logged or audited.
 *
 * @param stages every stage in queue order with how many Findings it holds, an empty one included
 * @param items this page of the queue, in queue order
 * @param page zero-based page number
 * @param size the page size asked for
 * @param total how many Findings the whole queue holds
 */
public record ReworkQueue(List<StageCount> stages, List<Item> items, int page, int size, long total) {

    /** How many Findings a stage holds. */
    public record StageCount(ReworkStage stage, long count) {}

    /**
     * One Finding of the queue.
     *
     * @param stage where it is in the queue
     * @param finding the Finding with its current proposal, as the Findings list reads it
     * @param dispute the Cyber Manager's latest review of the Finding when it Disputed (story 61): for a Disputed Finding,
     *     the Dispute of its current proposal and why; null when the Cyber Manager's last word on it was not a Dispute, or
     *     there is none
     */
    public record Item(ReworkStage stage, FindingView finding, @Nullable ReviewView dispute) {

        static Item of(ReworkStage stage, FindingView finding) {
            ReviewView latest = finding.latestReview();
            return new Item(stage, finding,
                    latest != null && latest.decision() == ReviewDecision.DISPUTED ? latest : null);
        }
    }
}
