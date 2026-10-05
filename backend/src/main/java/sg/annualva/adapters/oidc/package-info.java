/**
 * MCC SSO adapter: the OIDC client registration with explicit endpoints, {@code private_key_jwt}
 * client authentication (ES512, the configured EC P-521 key), PKCE and {@code kc_idp_hint} on the
 * authorization request, the published JWKS, and the mapping of MCC claims to an identity.
 * Depends on boundary and web only.
 */
package sg.annualva.adapters.oidc;
