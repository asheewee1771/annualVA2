package sg.annualva.web;

import org.jspecify.annotations.Nullable;

/**
 * Told about every request the application refuses with a 401 or a 403 (IM8 lm-4), at the moment the problem is
 * written, so the refusal can be recorded whichever part of the stack refused it. The access module implements it:
 * it knows who the caller is.
 */
public interface RefusalListener {

    /**
     * @param status 401 or 403
     * @param reason a fixed code: {@code unauthenticated}, {@code forbidden}, {@code csrf-rejected} or
     *     {@code step-up-required}; never anything taken from the request
     * @param intent for {@code step-up-required}, the code of the sensitive action; otherwise null
     */
    record Refusal(int status, String reason, @Nullable String intent) {

        public Refusal(int status, String reason) {
            this(status, reason, null);
        }
    }

    void refused(Refusal refusal);
}
