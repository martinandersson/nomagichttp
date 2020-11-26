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
        builder("/").expect("/");
        builder("/abc").expect("/abc");
        builder("/a/b/c").expect("/a/b/c");
        
        builder("/").param("one").concat("/a").param("two", "three").concat("/b").param("four")
                .expect("/{one}/a/{two}/{three}/b/{four}");
    }
    
    RouteBuilder builder;
    
    DefaultRouteToStringTest builder(String segment) {
         builder = new RouteBuilder(segment);
         return this;
    }
    
    DefaultRouteToStringTest concat(String segment) {
        builder.concat(segment);
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