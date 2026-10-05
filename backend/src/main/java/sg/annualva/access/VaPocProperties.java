package sg.annualva.access;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two VA POCs, set at deployment ({@code annualva.va-pocs}, a comma-separated list) and changed
 * only by changing the deployment's configuration through its change request. The application refuses
 * to start unless exactly two distinct email addresses are given. Values are never echoed in the
 * failure: the message names the setting, not what was set.
 *
 * @param vaPocs the VA POCs' email addresses
 */
@ConfigurationProperties("annualva")
public record VaPocProperties(List<String> vaPocs) {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");

    public VaPocProperties {
        List<String> emails = vaPocs == null ? List.of() : vaPocs.stream().map(String::trim).toList();
        if (emails.size() != 2) {
            throw new IllegalArgumentException("annualva.va-pocs must name exactly two VA POC email addresses");
        }
        if (!emails.stream().allMatch(email -> EMAIL.matcher(email).matches())) {
            throw new IllegalArgumentException("annualva.va-pocs must hold email addresses");
        }
        if (new HashSet<>(emails.stream().map(email -> email.toLowerCase(Locale.ROOT)).toList()).size() != 2) {
            throw new IllegalArgumentException("annualva.va-pocs must name two different VA POCs");
        }
        vaPocs = emails;
    }
}
