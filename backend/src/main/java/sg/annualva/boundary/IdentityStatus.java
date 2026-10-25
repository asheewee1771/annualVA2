package sg.annualva.boundary;

/**
 * The identity provider's account status (IM8 ac-3, ticket 47, ADR 0056): whether the provider has disabled a person since
 * they signed in. The session check asks it for a signed-in person at most every few minutes, so someone disabled there
 * loses access at once instead of keeping their session until it times out. The production adapter is MCC SSO's
 * account-status client, which waits on the onboarding of the service client (ticket 51, H4); the Seam 1 harness fakes it.
 *
 * <p>Every method throws {@link BoundaryFailureException} when the provider cannot be reached or gives no usable answer;
 * the caller then leaves the session as it was and asks again later. A call must end well within a request, so an adapter
 * sets its own connect and read timeouts of a few seconds.
 */
public interface IdentityStatus {

    /**
     * @param ssoId the person's identifier at the provider ({@code afcas.uuid}); never logged by an adapter
     * @return true when the provider reports the person disabled
     */
    boolean disabled(String ssoId);
}
