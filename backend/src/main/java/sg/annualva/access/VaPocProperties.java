package sg.annualva.access;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two VA POCs, set at deployment ({@code annualva.va-pocs}, a comma-separated list) and changed
 * only by changing the deployment's configuration through its change request. The application refuses
 * to start unless exactly two distinct email addresses are given. Values are never echoed in the
 * failure: the message names the setting, not what was set.
 *
 * <p>A VA POC's role that lapsed after 90 days unused comes back only through configuration (story 80a, ADR 0054): the
 * change request lists them in {@code annualva.va-poc-role-returns}, and the startup that follows returns their lapsed grant.
 * The list may name only the configured VA POCs; it is empty by default and is emptied again by the next change.
 *
 * @param vaPocs the VA POCs' email addresses
 * @param vaPocRoleReturns the VA POCs whose lapsed role the next startup returns
 */
@ConfigurationProperties("annualva")
public record VaPocProperties(List<String> vaPocs, List<String> vaPocRoleReturns) {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");

    public VaPocProperties {
        List<String> emails = vaPocs == null ? List.of() : vaPocs.stream().map(String::trim).toList();
        if (emails.size() != 2) {
            throw new IllegalArgumentException("annualva.va-pocs must name exactly two VA POC email addresses");
        }
        if (!emails.stream().allMatch(email -> EMAIL.matcher(email).matches())) {
            throw new IllegalArgumentException("annualva.va-pocs must hold email addresses");
        }
        if (new HashSet<>(emails.stream().map(VaPocProperties::lower).toList()).size() != 2) {
            throw new IllegalArgumentException("annualva.va-pocs must name two different VA POCs");
        }
        List<String> returns = vaPocRoleReturns == null ? List.of() : vaPocRoleReturns.stream().map(String::trim).toList();
        Set<String> configured = Set.copyOf(emails.stream().map(VaPocProperties::lower).toList());
        if (!returns.stream().map(VaPocProperties::lower).allMatch(configured::contains)) {
            throw new IllegalArgumentException("annualva.va-poc-role-returns may name only the configured VA POCs");
        }
        vaPocs = emails;
        vaPocRoleReturns = returns;
    }

    private static String lower(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
