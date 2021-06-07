package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.message.MediaType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.MediaType.TEXT_PLAIN;
import static alpha.nomagichttp.message.MediaType.__ALL;
import static alpha.nomagichttp.message.MediaType.__NOTHING;
import static alpha.nomagichttp.message.MediaType.__NOTHING_AND_ALL;
import static alpha.nomagichttp.message.MediaType.parse;
import static alpha.nomagichttp.message.Responses.accepted;
import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@code Route.lookup()}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RouteHandlerLookupTest
{
    @Test
    void simple() {
        RequestHandler target = create("text/plain", "text/plain");
        assertThat(exec("text/plain", "text/plain")).isSameAs(target);
    }
    
    @Test
    void simple_with_salt() {
                                create("text/plain", "text/html");
        RequestHandler target = create("text/plain", "text/plain");
                                create("text/html",  "*/*");
        
        assertThat(exec("text/plain", "text/plain")).isSameAs(target);
    }
    
    @Test
    void no_match_1() { 
        create("text/plain", "text/plain");
        
        assertThatThrownBy(() -> exec("gEt", TEXT_PLAIN, TEXT_PLAIN))
                .isExactlyInstanceOf(NoHandlerFoundException.class)
                .hasMessage("No handler found for method token \"gEt\".");
    }
    
    @Test
    void no_match_2() {
        create("text/plain", "text/plain");
        
        // Try lookup content-type "text/html"
        assertThatThrownBy(() -> exec("text/html", "text/plain"))
                .isExactlyInstanceOf(NoHandlerFoundException.class)
                .hasMessage("No handler found matching \"Content-Type: text/html\" and/or \"Accept: text/plain\" header in request.");
    }
    
    @Test
    void no_match_3() {
        create("text/plain", "text/plain");
        
        // Try lookup accept "text/html"
        assertThatThrownBy(() -> exec("text/plain", "text/html"))
                .isExactlyInstanceOf(NoHandlerFoundException.class)
                .hasMessage("No handler found matching \"Content-Type: text/plain\" and/or \"Accept: text/html\" header in request.");
    }
    
    @Test
    void no_match_4() {
        create("text/plain", "text/plain");
        
        // Try lookup accept "text/plain; q=0"
        assertThatThrownBy(() -> exec("text/plain", "text/plain; q=0"))
                .isExactlyInstanceOf(NoHandlerFoundException.class)
                .hasMessage("No handler found matching \"Content-Type: text/plain\" and/or \"Accept: text/plain; q=0\" header in request.");
    }
    
    @Test
    void ambiguous_by_consumes() {
        Set<RequestHandler> ambiguous = Set.of(
                create("text/plain", "text/what"),
                create("text/plain", "text/ever"));
        
        // Accepts "*/*"
        assertThatThrownBy(() -> exec("text/plain"))
                .isExactlyInstanceOf(AmbiguousNoHandlerFoundException.class)
                .extracting(e -> ((AmbiguousNoHandlerFoundException) e).getCandidates())
                .isEqualTo(ambiguous);
    }
    
    // Solve ambiguity by providing a more generic fallback.
    @Test
    void ambiguous_solution_1() {
                                create("text/plain", "text/what");
                                create("text/plain", "text/ever");
        RequestHandler target = create("text/*",     "text/html");
        
        assertThat(exec("text/plain")).isSameAs(target);
    }
    
    // ..or by being more specific.
    @Test
    void ambiguous_solution_2() {
                                create("text/plain", "*/*");
        RequestHandler target = create("text/plain", "more/specific");
        
        assertThat(exec("text/plain")).isSameAs(target);
    }
    
    @Test
    void order_by_specificity_1() {
                                create(__NOTHING_AND_ALL, __ALL);
        RequestHandler target = create(__NOTHING_AND_ALL, TEXT_PLAIN);
        
        assertThat(exec(null, "text/*")).isSameAs(target);
    }
    
    @Test
    void order_by_specificity_2() {
        RequestHandler target = create(__NOTHING, __ALL);
                                create(__NOTHING_AND_ALL, __ALL);
        
        // In this case, NOTHING is more specific than NOTHING_AND_ALL
        assertThat(exec((MediaType) null)).isSameAs(target);
    }
    
    @Test
    void order_by_specificity_3() {
                                create(__NOTHING, __ALL);
        RequestHandler target = create(__NOTHING_AND_ALL, __ALL);
        
        // In this case, NOTHING_AND_ALL is more specific than NOTHING
        assertThat(exec("bla/bla")).isSameAs(target);
    }
    
    @Test
    void order_by_specificity_4() {
                                create(__NOTHING_AND_ALL, __ALL);
        RequestHandler target = create(__ALL, __ALL);
        
        // In this case, ALL is more specific than NOTHING_AND_ALL
        assertThat(exec("bla/bla")).isSameAs(target);
    }
    
    @Test
    void consumes_params_specific() {
        RequestHandler target = create("text/plain; charset=utf-8; something=else", "*/*");
        
        assertThatThrownBy(() -> exec("text/plain", "*/*"))
                .isExactlyInstanceOf(NoHandlerFoundException.class)
                .hasMessage("No handler found matching \"Content-Type: text/plain\" and/or \"Accept: */*\" header in request.");
        
        assertThat(exec("text/plain; something = else; chArsEt=\"  UtF-8  \"", "*/*"))
                .isSameAs(target);
    }
    
    @Test
    void consumes_params_all() {
        RequestHandler target = create("text/html", "*/*");
        assertThat(exec("*/*;       bla=bla", "*/*")).isSameAs(target);
        assertThat(exec("text/*;    bla=bla", "*/*")).isSameAs(target);
        assertThat(exec("text/html; bla=bla", "*/*")).isSameAs(target);
    }
    
    @Test
    void produces_params_specific() {
        RequestHandler target = create(__NOTHING_AND_ALL, parse("text/plain; charset=utf-8; something=else"));
        
        assertThatThrownBy(() -> exec(null, "*/*"))
                .isExactlyInstanceOf(NoHandlerFoundException.class)
                .hasMessage("No handler found matching \"Content-Type: [N/A]\" and/or \"Accept: */*\" header in request.");
        
        assertThat(exec(null, "text/plain; something = else; chArsEt=\"  UtF-8  \""))
                .isSameAs(target);
    }
    
    @Test
    void produces_params_all() {
        RequestHandler target = create(__NOTHING_AND_ALL, parse("text/html"));
        assertThat(exec(__NOTHING_AND_ALL, parse("*/*;       bla=bla"))).isSameAs(target);
        assertThat(exec(__NOTHING_AND_ALL, parse("text/*;    bla=bla"))).isSameAs(target);
        assertThat(exec(__NOTHING_AND_ALL, parse("text/html; bla=bla"))).isSameAs(target);
    }
    
    @Test
    void stupid_client() {
        create(__NOTHING_AND_ALL, __ALL);
        // Client has nothing to provide us and accepts nothing in return.
        assertThatThrownBy(() -> exec(null, "*/*;q=0"))
                .isExactlyInstanceOf(NoHandlerFoundException.class)
                .hasMessage("No handler found matching \"Content-Type: [N/A]\" and/or \"Accept: */*;q=0\" header in request.");
    }
    
    // RFC 7231, section "5.3.2. Accept", gives this example:
    // (https://tools.ietf.org/html/rfc7231#page-38)
    // 
    // Accept: text/*;q=0.3, text/html;q=0.7, text/html;level=1,
    //          text/html;level=2;q=0.4, */*;q=0.5
    // 
    // would cause the following values to be associated:
    // 
    // +-------------------+---------------+
    // | Media Type        | Quality Value |
    // +-------------------+---------------+
    // | text/html;level=1 | 1             |
    // | text/html         | 0.7           |
    // | text/plain        | 0.3           |
    // | image/jpeg        | 0.5           |
    // | text/html;level=2 | 0.4           |
    // | text/html;level=3 | 0.7           |
    // +-------------------+---------------+
    @Test
    void example_from_rfc_7231() {
        final String[] accepts = {
                "text/*;q=0.3",
                "text/html;q=0.7",
                "text/html;level=1",
                "text/html;level=2;q=0.4",
                "*/*;q=0.5"};
        
        // "text/plain" is the client's last preference, but the only one we have.
        RequestHandler target = create(__NOTHING_AND_ALL, parse("text/plain"));
        assertThat(exec(null, accepts)).isSameAs(target);
        
        // So we keep adding more preferred handlers (the "given type" in RFC),
        // and the same request gets routed to the new handlers accordingly.
        
        target = create(__NOTHING_AND_ALL, parse("text/html;level=2"));
        assertThat(exec(null, accepts)).isSameAs(target);
        
        target = create(__NOTHING_AND_ALL, parse("image/jpeg"));
        assertThat(exec(null, accepts)).isSameAs(target);
        
        target = create(__NOTHING_AND_ALL, parse("text/html"));
        assertThat(exec(null, accepts)).isSameAs(target);
        
        // Same Q ("0.7") as last one and media type even more specific.
        // But handler specifies a parameter and request doesn't.
        // So the more generic handler "text/html" with no params is still the match.
        create(__NOTHING_AND_ALL, parse("text/html;level=3"));
        assertThat(exec(null, accepts)).isSameAs(target);
        
        target = create(__NOTHING_AND_ALL, parse("text/html;level=1"));
        assertThat(exec(null, accepts)).isSameAs(target);
    }
    
    
    
    final List<RequestHandler> handlers = new ArrayList<>();
    
    private RequestHandler create(String consumes, String produces) {
        return create(parse(consumes), parse(produces));
    }
    
    private RequestHandler create(MediaType consumes, MediaType produces) {
        RequestHandler h = GET().consumes(consumes)
                                .produces(produces)
                                .respond(accepted());
        handlers.add(h);
        return h;
    }
    
    private RequestHandler exec(String contentType, String... accepts) {
        return exec("GET",
                contentType == null ? null : parse(contentType),
                accepts == null ? null : stream(accepts).map(MediaType::parse).toArray(MediaType[]::new));
    }
    
    private RequestHandler exec(MediaType contentType, MediaType... accepts) {
        return exec("GET", contentType, accepts);
    }
    
    private RequestHandler exec(String method, MediaType contentType, MediaType... accepts) {
        Route.Builder b = Route.builder("/blabla");
        handlers.forEach(b::handler);
        return b.build().lookup(method, contentType, accepts);
    }
}