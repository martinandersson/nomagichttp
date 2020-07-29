package alpha.nomagichttp.route;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provokes exceptions from {@code RouteBuilder} caused by invalid segment- and
 * parameter name arguments.<p>
 * 
 * For the inverse (what builds a valid route)m see {@link RouteMatchesTest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RouteBuilderInvalidInputTest
{
    @Test
    void segment_can_not_be_empty_ctor() {
        assertThatThrownBy(() -> new RouteBuilder(""))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("A segment must start with a \"/\" character. Got: \"\"");
    }
    
    @Test
    void segment_can_not_be_empty_concat_1() {
        RouteBuilder rb = new RouteBuilder("/");
        
        assertThatThrownBy(() -> rb.concat(""))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("A segment must start with a \"/\" character. Got: \"\"");
    }
    
    @Test
    void segment_can_not_be_empty_concat_2() {
        RouteBuilder rb = new RouteBuilder("/");
        
        assertThatThrownBy(() -> rb.concat("/"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Segment must contain more than just a forward slash.");
    }
    
    @Test
    void segment_must_start_with_slash_ctor() {
        assertThatThrownBy(() -> new RouteBuilder("x"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("A segment must start with a \"/\" character. Got: \"x\"");
    }
    
    @Test
    void segment_must_start_with_slash_concat() {
        RouteBuilder rb = new RouteBuilder("/");
        
        assertThatThrownBy(() -> rb.concat("x"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("A segment must start with a \"/\" character. Got: \"x\"");
    }
    
    @Test
    void segment_can_not_be_two_slashes_ctor() {
        assertThatThrownBy(() -> new RouteBuilder("//"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Segment must contain more than just forward slash(es).");
    }
    
    @Test
    void segment_can_not_be_two_slashes_concat() {
        RouteBuilder rb = new RouteBuilder("/");
        
        assertThatThrownBy(() -> rb.concat("//"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Segment must contain more than just forward slash(es).");
    }
    
    @Test
    void segment_can_not_have_two_trailing_slashes_ctor() {
        assertThatThrownBy(() -> new RouteBuilder("/a//"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Multiple trailing forward slashes in segment: \"/a//\"");
    }
    
    @Test
    void segment_can_not_have_two_trailing_slashes_concat() {
        RouteBuilder rb = new RouteBuilder("/");
    
        assertThatThrownBy(() -> rb.concat("/a//"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Multiple trailing forward slashes in segment: \"/a//\"");
    }
    
    @Test
    void segment_can_not_be_null_ctor() {
        assertThatThrownBy(() -> new RouteBuilder(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage(null);
    }
    
    @Test
    void segment_can_not_be_null_concat() {
        RouteBuilder rb = new RouteBuilder("/");
        
        assertThatThrownBy(() -> rb.concat(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage(null);
    }
    
    @Test
    void param_can_not_be_null() {
        RouteBuilder rb = new RouteBuilder("/");
        
        assertThatThrownBy(() -> rb.param(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage(null);
    }
    
    @Test
    void param_name_must_be_unique() {
        RouteBuilder rb = new RouteBuilder("/").param("name");
        
        assertThatThrownBy(() -> rb.param("name"))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate parameter name: \"name\"");
    }
}