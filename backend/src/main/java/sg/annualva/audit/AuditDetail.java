package sg.annualva.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The allow-listed detail of an audit entry: keys from {@link DetailKey} only, values that are a short code
 * (letters, digits and {@code . _ : -}, at most 64), an id, a number or a yes/no flag. A value that is not one of those is a
 * programming error and is refused without being echoed, so free text cannot get in by construction (lm-19).
 */
public final class AuditDetail {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final AuditDetail NONE = new AuditDetail(Map.of());

    private final Map<String, Object> values;

    private AuditDetail(Map<String, Object> values) {
        this.values = values;
    }

    public static AuditDetail none() {
        return NONE;
    }

    public AuditDetail with(DetailKey key, String code) {
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("An audit detail value is a short code, not text");
        }
        return plus(key, code);
    }

    public AuditDetail with(DetailKey key, UUID id) {
        return plus(key, id.toString());
    }

    public AuditDetail with(DetailKey key, long number) {
        return plus(key, number);
    }

    public AuditDetail with(DetailKey key, boolean flag) {
        return plus(key, flag);
    }

    /** The detail as stored: key to code, id, number or flag. */
    Map<String, Object> values() {
        return values;
    }

    private AuditDetail plus(DetailKey key, Object value) {
        var copy = new LinkedHashMap<>(values);
        copy.put(key.json(), value);
        return new AuditDetail(Collections.unmodifiableMap(copy));
    }
}
