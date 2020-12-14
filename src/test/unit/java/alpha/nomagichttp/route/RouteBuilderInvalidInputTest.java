package alpha.nomagichttp.route;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.route.Route.builder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * Provokes exceptions from {@code Route.Builder} caused by invalid segment- and
 * parameter name arguments.<p>
 * 
 * For the inverse (what builds a valid route) see {@link RouteMatchesTest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RouteBuilderInvalidInputTest
{
    @Test
    void segment_can_not_be_empty_ctor() {
        assertThrows(() -> Route.builder(""),
                IllegalArgumentException.class,
                "Segment must start with a forward slash character.");
    }
    
    @Test
    void segment_can_not_be_empty_concat() {
        Route.Builder rb = builder("/");
        assertThrows(() -> rb.append("/"),
                IllegalArgumentException.class,
                "Segment must contain more than just a forward slash character.");
    }
    
    @Test
    void segment_can_not_be_two_slashes_ctor() {
        assertThrows(() -> builder("//"),
                IllegalArgumentException.class,
                "Segment is empty.");
    }
    
    @Test
    void segment_can_not_be_two_slashes_concat() {
        Route.Builder rb = builder("/");
        assertThrows(() -> rb.append("//"),
                IllegalArgumentException.class,
                "Segment must not end with a forward slash character.");
    }
    
    @Test
    void segment_can_not_be_null() {
        assertThrows(() -> builder(null), NullPointerException.class, null);
    }
    
    @Test
    void param_can_not_be_null() {
        Route.Builder rb = builder("/");
        assertThrows(() -> rb.param(null), NullPointerException.class, null);
    }
    
    @Test
    void param_name_must_be_unique() {
        Route.Builder rb = builder("/").param("name");
        assertThrows(() -> rb.param("name"),
                IllegalArgumentException.class,
                "Duplicate parameter name: \"name\"");
    }
    
    private static void assertThrows(ThrowingCallable code, Class<?> exactlyInstanceOf, String hasMessage) {
        assertThatThrownBy(code)
                .isExactlyInstanceOf(exactlyInstanceOf)
                .hasMessage(hasMessage);
    }
}