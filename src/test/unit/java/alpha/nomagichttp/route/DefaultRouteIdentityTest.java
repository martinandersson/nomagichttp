package alpha.nomagichttp.route;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.handler.RequestHandlers.noop;
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
        init("/").expect("/");
        init("/abc").expect("/abc");
        init("/a/b/c").expect("/a/b/c");
        
        init("/").param("one").append("/a").param("two", "three").append("/b").param("four")
                .expect("/a/b");
    }
    
    Route.Builder builder;
    
    DefaultRouteIdentityTest init(String segment) {
         builder = Route.builder(segment);
         return this;
    }
    
    DefaultRouteIdentityTest append(String segment) {
        builder.append(segment);
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