/** Shown when there is no session. Sign-in goes through the organisation single sign-on. */
export function SignedOutScreen() {
  return (
    <main id="main" className="shell-main">
      <div className="card">
        <h2>Sign in to Annual VA</h2>
        <p className="muted">
          Use the organisation single sign-on. Multi-factor authentication is required at every sign-in.
        </p>
        <a className="button" href="/oauth2/authorization/mcc-sso-login">
          Sign in
        </a>
      </div>
    </main>
  );
}
