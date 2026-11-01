package rs.pametnakupovina.backend.shoppinglist;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The {@code {listId}} of the path, as the list's number. Clients may send
 * the number (Android up to 2.0.6) or the list's random id; see
 * {@link ListRefArgumentResolver}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ListRef {
}
