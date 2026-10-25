package sg.annualva.access;

import java.util.Locale;

/**
 * Why a lapsed grant came back by itself (story 80a, IM8 ac-3, ADR 0055). Stored on the grant as the name and audited as
 * the lower-case code ({@code quarter-start}, say).
 */
public enum ReturnTrigger {
    /** A Register apply named the person again for the same role and System. */
    REGISTER,
    /** The Quarter of a System the person is named on began. */
    QUARTER_START,
    /** A VA Submission of the System was returned for rework to its SMO/Tech Lead. */
    RETURN_FOR_REWORK,
    /** A VA Submission of the System was submitted for review to its Cyber Manager. */
    SUBMITTED_FOR_REVIEW,
    /** A conditional Exemption of the System lapsed back with new windows. */
    EXEMPTION_LAPSE,
    /** The deployment's configuration returned a VA POC at startup: the only way a VA POC comes back. */
    CONFIGURATION;

    /** The audited reason. */
    String code() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
