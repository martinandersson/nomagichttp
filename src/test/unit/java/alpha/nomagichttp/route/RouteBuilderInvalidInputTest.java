package alpha.nomagichttp.route;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.route.Route.builder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThatThrownBy(() -> Route.builder(""))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Segment must start with a forward slash character.");
    }
    
    @Test
    void segment_can_not_be_empty_concat() {
        Route.Builder rb = builder("/");
        
        assertThatThrownBy(() -> rb.append("/"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Segment must contain more than just a forward slash character.");
    }
    
    @Test
    void segment_can_not_be_two_slashes_ctor() {
        assertThatThrownBy(() -> builder("//"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Segment is empty.");
    }
    
    @Test
    void segment_can_not_be_two_slashes_concat() {
        Route.Builder rb = builder("/");
        
        assertThatThrownBy(() -> rb.append("//"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Segment must not end with a forward slash character.");
    }
    
    @Test
    void segment_can_not_be_null() {
        assertThatThrownBy(() -> builder(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage(null);
    }
    
    @Test
    void param_can_not_be_null() {
        Route.Builder rb = builder("/");
        
        assertThatThrownBy(() -> rb.param(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage(null);
    }
    
    @Test
    void param_name_must_be_unique() {
        Route.Builder rb = builder("/").param("name");
        
        assertThatThrownBy(() -> rb.param("name"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate parameter name: \"name\"");
    }
}