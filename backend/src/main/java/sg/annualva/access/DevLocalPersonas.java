package sg.annualva.access;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The six personas of a developer's machine (ticket 63): who they are comes from the existing dev identities
 * ({@code annualva.dev.identities}, the {@code afcas.uuid} each one has and the email the System Register knows them by);
 * only the name and the role label shown on the picker are added here ({@code annualva.dev.personas}). A persona carries
 * no role: what it can do is whatever the Register says about its email, as for anyone.
 */
@Component
@Profile("dev-local")
class DevLocalPersonas {

    /**
     * What the picker shows.
     *
     * @param id the persona's {@code afcas.uuid}, the key the sign-in takes
     * @param roleLabel the role the Register upload in {@code scripts/dev-data/} gives the persona: a label for the
     *     developer, not a grant
     */
    public record Persona(String id, String name, String roleLabel, String email) {}

    /** Names and labels by {@code afcas.uuid} (prefix {@code annualva.dev}, beside the identities). */
    @ConfigurationProperties("annualva.dev")
    @Profile("dev-local")
    record Labels(@DefaultValue Map<String, Label> personas) {}

    record Label(String name, String roleLabel) {}

    private final List<Persona> personas;

    DevLocalPersonas(Labels labels, DevIdentityProperties identities) {
        this.personas = labels.personas().entrySet().stream().map(entry -> {
            String email = identities.identities().get(entry.getKey());
            if (email == null) {
                throw new IllegalStateException("A dev-local persona has no entry in annualva.dev.identities, so the "
                        + "System Register could not know it by email");
            }
            return new Persona(entry.getKey(), entry.getValue().name(), entry.getValue().roleLabel(), email);
        }).toList();
        if (personas.isEmpty()) {
            throw new IllegalStateException("The dev-local profile has no persona to sign in as (annualva.dev.personas)");
        }
    }

    List<Persona> all() {
        return personas;
    }

    Optional<Persona> find(String id) {
        return personas.stream().filter(persona -> persona.id().equals(id)).findFirst();
    }
}
