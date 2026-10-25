package sg.annualva.access;

import java.util.Locale;

/**
 * Why a grant is suspended (ticket 47, ADR 0056). Stored on the grant as the name and audited as the lower-case code
 * ({@code identity-provider}, say).
 */
public enum SuspensionCause {
    /** A VA POC suspended it in the role-holder review (IM8 ac-4). */
    REVIEW,
    /** The identity provider reported the person disabled (IM8 ac-3). */
    IDENTITY_PROVIDER;

    String code() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
