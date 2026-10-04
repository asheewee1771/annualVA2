# Spring profile contract: base is the production shape

Status: accepted

`application.yml` is the production shape: every environment-specific or secret property is a bare `${ENV_VAR}` binding with no literal and no default, and only invariant structural settings are literals. `application-dev-mcc.yml` carries the mock values for the local stack. `application-ci.yml` lives on the test classpath only. `application-sit.yml` (added by the first SIT-bound ticket) uses `${VAR:dev-default}` so it is self-contained locally, with a committed `.env.sit.example` and a small `EnvironmentPostProcessor` that loads `.env.{profile}`. UAT and production use bare `${VAR}` and fail fast.

Consequences: a deployed environment missing a value fails at start rather than falling back to a mock; every new `${VAR}` in the SIT profile needs a matching key in `.env.sit.example`; an empty value in a dotenv file overrides the default, so only overridden keys belong there.
