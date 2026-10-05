package sg.annualva.access;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Local-stack identities (prefix {@code annualva.dev}): MockPass personas carry an
 * {@code afcas.uuid} but no email, while the System Register works by email. The map is consulted
 * only when a token has no email claim; it is empty outside the {@code dev-mcc} profile.
 *
 * @param identities {@code afcas.uuid} to email
 */
@ConfigurationProperties("annualva.dev")
public record DevIdentityProperties(@DefaultValue Map<String, String> identities) {}
