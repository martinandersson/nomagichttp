package alpha.nomagichttp.route;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.handler.Handlers.noop;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code DefaultRoute.identity()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultRouteIdentityTest
{
    @Test
    void all() {
        builder("/").expect("/");
        builder("/abc").expect("/abc");
        builder("/a/b/c").expect("/a/b/c");
        
        builder("/").param("one").concat("/a").param("two", "three").concat("/b").param("four")
                .expect("/a/b");
    }
    
    RouteBuilder builder;
    
    DefaultRouteIdentityTest builder(String segment) {
         builder = new RouteBuilder(segment);
         return this;
    }
    
    DefaultRouteIdentityTest concat(String segment) {
        builder.concat(segment);
        return this;
    }
    
    DefaultRouteIdentityTest param(String param, String... more) {
        builder.param(param, more);
        return this;
    }
    
    void expect(String identity) {
        assertThat(builder.handler(noop()).build().identity())
                .isEqualTo(identity);
    }
}