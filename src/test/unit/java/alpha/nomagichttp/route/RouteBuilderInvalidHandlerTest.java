package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.DefaultRequestHandler;
import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.handler.HandlerBuilder;
import alpha.nomagichttp.message.MediaType;
import org.junit.jupiter.api.Test;

import static alpha.nomagichttp.message.MediaType.ALL;
import static alpha.nomagichttp.message.MediaType.NOTHING;
import static alpha.nomagichttp.message.MediaType.NOTHING_AND_ALL;
import static alpha.nomagichttp.handler.Handlers.noop;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provokes {@code HandlerCollisionException} from {@code RouteBuilder}.<p>
 * 
 * For the inverse (what builds a valid route)m see {@link RouteMatchesTest}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RouteBuilderInvalidHandlerTest
{
    RouteBuilder testee = new RouteBuilder("/");
    
    @Test
    void already_added() {
        testee.handler(noop());
        
        assertThatThrownBy(() -> testee.handler(noop()))
                .isExactlyInstanceOf(HandlerCollisionException.class)
                .hasMessage("An equivalent handler has already been added: " +
                        DefaultRequestHandler.class.getSimpleName() + "{method=\"GET\", consumes=\"<nothing and all>\", produces=\"*/*\"}");
    }
    
    @Test
    void consumes_ambiguity() {
        testee.handler(create(NOTHING));
        testee.handler(create(NOTHING_AND_ALL));
        
        assertThatThrownBy(() -> testee.handler(create(ALL)))
                .isExactlyInstanceOf(HandlerCollisionException.class)
                .hasMessage("All other meta data being equal; if there's a consumes <nothing> then <nothing and all> is effectively equal to */*.");
    }
    
    private RequestHandler create(MediaType consumes) {
        return new HandlerBuilder("GET")
                .consumes(consumes)
                .producesAll()
                .run(() -> {});
    }
}
