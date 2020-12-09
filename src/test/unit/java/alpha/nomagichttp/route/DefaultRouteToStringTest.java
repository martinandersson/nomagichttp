package alpha.nomagichttp.route;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.handler.RequestHandlers.noop;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code DefaultRoute.toString()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultRouteToStringTest
{
    @Test
    void all() {
        init("/").expect("/");
        init("/abc").expect("/abc");
        init("/a/b/c").expect("/a/b/c");
        
        init("/").param("one").append("/a").param("two", "three").append("/b").param("four")
                .expect("/{one}/a/{two}/{three}/b/{four}");
    }
    
    Route.Builder builder;
    
    DefaultRouteToStringTest init(String segment) {
         builder = Route.builder(segment);
         return this;
    }
    
    DefaultRouteToStringTest append(String segment) {
        builder.append(segment);
        return this;
    }
    
    DefaultRouteToStringTest param(String param, String... more) {
        builder.param(param, more);
        return this;
    }
    
    void expect(String toString) {
        assertThat(builder.handler(noop()).build().toString())
                .isEqualTo(toString);
    }
}