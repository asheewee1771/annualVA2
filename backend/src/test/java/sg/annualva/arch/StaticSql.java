package sg.annualva.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * The static SQL of the application's stores, for the tests that say which store may name which table. SQL is written
 * only in stores ({@code ModuleConventionsArchTest.jdbcClientIsUsedOnlyInsideStores}, ADR 0001), and every statement is a
 * static {@code String} constant, so reading each constant's value by reflection, with every concatenation already
 * resolved, sees every statement as it is sent, a fragment it is built from included.
 */
final class StaticSql {

    /**
     * One static {@code String} of a store.
     *
     * @param store the store's fully qualified class name
     * @param field the constant's name
     * @param text its value, as the statement is sent
     */
    record Constant(String store, String field, String text) {

        /** {@code Store.FIELD}, as a test names it. */
        String name() {
            return store.substring(store.lastIndexOf('.') + 1) + "." + field;
        }

        String packageName() {
            return store.substring(0, store.lastIndexOf('.'));
        }
    }

    private StaticSql() {}

    /** Every static {@code String} of every {@code @Repository} class among these classes. */
    static List<Constant> of(JavaClasses classes) throws IllegalAccessException {
        List<Constant> constants = new ArrayList<>();
        for (JavaClass store : classes) {
            if (!store.isAnnotatedWith(Repository.class)) {
                continue;
            }
            for (Field field : store.reflect().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                    field.setAccessible(true);
                    if (field.get(null) instanceof String text) {
                        constants.add(new Constant(store.getName(), field.getName(), text));
                    }
                }
            }
        }
        return constants;
    }
}
