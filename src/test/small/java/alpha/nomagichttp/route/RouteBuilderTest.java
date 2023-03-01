package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;
import org.junit.jupiter.api.Test;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.MediaType.ALL;
import static alpha.nomagichttp.message.MediaType.NOTHING;
import static alpha.nomagichttp.message.MediaType.NOTHING_AND_ALL;
import static alpha.nomagichttp.message.Responses.accepted;
import static java.util.List.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests building a route.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RouteBuilderTest
{
    Route.Builder testee;
    
    @Test
    void root() {
        testee = builder("/");
        assertSegments();
        assertToString("/");
    }
    
    @Test
    void simple() {
        testee = builder("/where");
        assertSegments("where");
        assertToString("/where");
    }
    
    @Test
    void two_segments() {
        testee = builder("/where/that");
        assertSegments("where", "that");
        assertToString("/where/that");
    }
    
    @Test
    void param_single() {
        testee = builder("/").paramSingle("p");
        assertSegments(":p");
        assertToString("/:p");
    }
    
    @Test
    void param_catch() {
        testee = builder("/").paramCatchAll("p");
        assertSegments("*p");
        assertToString("/*p");
    }
    
    @Test
    void param_emptyName() {
        testee = builder("/").paramSingle("");
        assertSegments(":");
        assertToString("/:");
    }
    
    @Test
    void mixed_pattern() {
        testee = builder("/user/:id/storage/:drive/*filepath");
        assertSegments("user", ":id", "storage", ":drive", "*filepath");
        assertToString("/user/:id/storage/:drive/*filepath");
    }
    
    @Test
    void mixed_api() {
        testee = builder("/user")
                .paramSingle("id")
                .append("storage")
                .paramSingle("drive")
                .paramCatchAll("filepath");
        
        assertSegments("user", ":id", "storage", ":drive", "*filepath");
        assertToString("/user/:id/storage/:drive/*filepath");
    }
    
    @Test
    void slash() {
        of("/x", "/x/").forEach(s -> {
            // builder() tolerates one redundant slash
            testee = builder(s);
            assertSegments("x");
            assertToString("/x");
            
            // append(), same
            testee = builder("/").append(s);
            assertSegments("x");
            assertToString("/x");
        });
        
        BiConsumer<String, Consumer<String>> assertParseExc = (s, f) ->
                assertThatThrownBy(() -> f.accept(s))
                        .isExactlyInstanceOf(RoutePatternInvalidException.class)
                        .cause()
                        .isExactlyInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Static segment value is empty.");
        
        BiConsumer<String, Consumer<String>> assertIllegalArg = (s, f) ->
                assertThatThrownBy(() -> f.accept(s))
                        .isExactlyInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Static segment value is empty.");
        
        // clusters of them is not okay!
        of("//", "x//", "/x//", "//x", "//x/", "//x//").forEach(s -> {
            // not in Route.builder() nor in Route.Builder.append()
            assertParseExc.accept(s, this::builder);
            assertIllegalArg.accept(s, ignored -> builder("/").append(s));
        });
        
        // and same exc when append() is called with just a slash
        assertIllegalArg.accept(null, ignored -> builder("/").append("/"));
    }
    
    @Test
    void no_handler() {
        assertThatThrownBy(() -> Route.builder("/").build())
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("No handlers.");
    }
    
    @Test
    void duplicated_param() {
        assertThatThrownBy(() -> builder("/:p/:p"))
                .isExactlyInstanceOf(RoutePatternInvalidException.class)
                .cause()
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicated parameter name: \"p\"");
    }
    
    @Test
    void catchAll_not_last() {
        assertThatThrownBy(() -> builder("/*catch-all/something"))
                .isExactlyInstanceOf(RoutePatternInvalidException.class)
                .cause()
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Catch-all path parameter must be the last segment.");
    }
    
    @Test
    void handler_already_added() {
        testee = Route.builder("/").handler(dummy());
        
        assertThatThrownBy(() -> testee.handler(dummy()))
                .isExactlyInstanceOf(HandlerCollisionException.class)
                .hasMessage("An equivalent handler has already been added: " +
                        "DefaultRequestHandler{method=\"GET\", consumes=\"<nothing and all>\", produces=\"*/*\", logic=?}");
    }
    
    @Test
    void handler_consumes_ambiguity() {
        testee = Route.builder("/");
        testee.handler(createHandler(NOTHING));
        testee.handler(createHandler(NOTHING_AND_ALL));
        
        assertThatThrownBy(() -> testee.handler(createHandler(ALL)))
                .isExactlyInstanceOf(HandlerCollisionException.class)
                .hasMessage("All other meta data being equal; if there's a consumes <nothing> then <nothing and all> is effectively equal to */*.");
    }
    
    private RequestHandler createHandler(MediaType consumes) {
        return GET().consumes(consumes)
                    .respond(accepted());
    }
    
    private Route.Builder builder(String pattern) {
        return Route.builder(pattern).handler(dummy());
    }
    
    private static RequestHandler dummy() {
        return GET().respond(accepted());
    }
    
    private void assertSegments(String... segments) {
        assertThat(route().segments()).containsSequence(of(segments));
    }
    
    private void assertToString(String expected) {
        assertThat(route().toString()).isEqualTo(expected);
    }
    
    Route r;
    
    private Route route() {
        return r != null ? r : (r = testee.build());
    }
}