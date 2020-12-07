package alpha.nomagichttp.route;

import org.junit.jupiter.api.Disabled;
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
    void only_identity_matters() {
        Route r1 = route("/", noop()),
              r2 = new RouteBuilder("/")
                      .param("only-identity-matters")
                      .handler(noop())
                      .build();
        
        testee.add(r1);
        assertThatThrownBy(() -> testee.add(r2))
                .isExactlyInstanceOf(RouteCollisionException.class)
                .hasMessage("The specified route \"/{only-identity-matters}\" is equivalent to an already added route \"/\".");
    }
    
    // Currently fails. This feature is not implemented.
    @Disabled
    @Test
    void ambiguous() {
        Route r1 = new RouteBuilder("/")
                       .param("p")
                       .handler(noop())
                       .build(),
              r2 = route("/segment", noop());
        
        testee.add(r1);
        assertThatThrownBy(() -> testee.add(r2))
                // We want AmbiguousRouteCollisionException? (extends RouteCollisionException)
                .isExactlyInstanceOf(RouteCollisionException.class)
                .hasMessage("Message to be defined.");
        
        // On the implementation, just test-match all routes against the id of the new guy.
        // No route should match the id, then we have a problem!
    }
}