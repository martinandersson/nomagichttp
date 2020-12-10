package alpha.nomagichttp.route;

import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.handler.RequestHandlers.noop;
import static alpha.nomagichttp.route.Routes.route;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link DefaultRouteRegistry}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultRouteRegistryTest
{
    private final RouteRegistry testee = new DefaultRouteRegistry();
    
    @Test
    void route_collision_1() {
        Route r1 = route("/", noop()),
              r2 = route("/", noop());
        
        testee.add(r1);
        assertThatThrownBy(() -> testee.add(r2))
                .isExactlyInstanceOf(RouteCollisionException.class)
                .hasMessage("The specified route \"/\" is equivalent to an already added route \"/\".");
    }
    
    @Test
    void route_collision_2() {
        Route r1 = route("/", noop()),
              r2 = Route.builder("/")
                      // Has same ID because params doesn't participate.
                      .param("p")
                      .handler(noop())
                      .build();
        
        testee.add(r1);
        assertThatThrownBy(() -> testee.add(r2))
                .isExactlyInstanceOf(RouteCollisionException.class)
                .hasMessage("The specified route \"/{p}\" is equivalent to an already added route \"/\".");
    }
    
    @Test
    void ambiguous() {
        Route r1 = Route.builder("/")
                       .param("p")
                       .handler(noop())
                       .build(),
              // If request path comes in as "/s", which route would it match? Ambiguous!
              r2 = route("/s", noop());
        
        testee.add(r1);
        assertThatThrownBy(() -> testee.add(r2))
                // We want AmbiguousRouteCollisionException? (extends RouteCollisionException)
                .isExactlyInstanceOf(AmbiguousRouteCollisionException.class)
                .hasMessage("Route \"/s\" is effectively equivalent to an already added route \"/{p}\".");
    }
}