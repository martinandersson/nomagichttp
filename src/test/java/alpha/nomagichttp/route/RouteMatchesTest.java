package alpha.nomagichttp.route;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static alpha.nomagichttp.handler.Handlers.noop;
import static java.util.Arrays.stream;
import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests different ways to combine segments and parameters into a route and what
 * that route matches with given different request-targets.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RouteMatchesTest
{
    RouteBuilder builder;
    
    @Test
    void simple_match_1() {
        builder = new RouteBuilder("/");
        
        assertMatches("/", of());
        
        assertMatchesNull("a/", "/a");
    }
    
    @Test
    void simple_match_2() {
        builder = new RouteBuilder("/a/b/c");
        
        assertMatches("/a/b/c", of());
        
        assertMatchesNull(
                "/x/b/c",
                "/a/x/c",
                "/a/b/x",
                "/a/b/c/x",
                "/x/a/b/c",
                "/a/b",
                "/b/c");
    }
    
    // We're quite "slash tolerant" when parsing input from HTTP
    
    @Test
    void simple_match_single_slash_tolerant_1() {
        builder = new RouteBuilder("/a/"); // <--slash
        assertMatches("/a", of());         // <-- no slash
    }
    
    @Test
    void simple_match_single_slash_tolerant_2() {
        builder = new RouteBuilder("/a"); // <-- no slash
        assertMatches("/a/", of());       // <-- slash
    }
    
    @Test
    void simple_match_single_slash_tolerant_3() {
        builder = new RouteBuilder("/a/"); // <-- slash
        assertMatches("/a/", of());        // <-- slash
    }
    
    @Test
    void simple_match_multiple_slash_tolerant_1() {
        builder = new RouteBuilder("/a");
        assertMatches("a", of());          // <-- no slash at all
    }
    
    @Test
    void not_tolerant_to_many_slashes_1() {
        builder = new RouteBuilder("/");
        assertMatchesNull("//", "///", "////");
    }
    
    @Test
    void not_tolerant_to_many_slashes_2() {
        builder = new RouteBuilder("/a");
        assertMatchesNull("/a//", "/a///", "/a////");
    }
    
    // TODO: Fix failing test.
    @Test
    void not_tolerant_to_many_slashes_3() {
        builder = new RouteBuilder("/a").param("x");
        assertMatchesNull("/a//", "/a///", "/a////");
    }
    
    @Test
    void param_one() {
        builder = new RouteBuilder("/").param("p1");
        
        assertMatches("/v1", of("p1", "v1"));
        assertMatches("/",   of()); // <-- params are always optional!
        
        assertMatchesNull("/param-value/but-not-this");
    }
    
    @Test
    void param_two_serially() {
        builder = new RouteBuilder("/").param("p1", "p2");
        
        assertMatches("/v1/v2", of("p1", "v1", "p2", "v2"));
        assertMatches("/v1",    of("p1", "v1"));
        assertMatches("/",      of());
        
        assertMatchesNull("/v1/v2/not-expected/");
    }
    
    @Test
    void param_three_serially() {
        builder = new RouteBuilder("/").param("p1", "p2", "p3");
        
        assertMatches("/v1/v2/v3", of("p1", "v1", "p2", "v2", "p3", "v3"));
        assertMatches("/v1/v2",    of("p1", "v1", "p2", "v2"));
        assertMatches("/v1/",      of("p1", "v1"));
        assertMatches("",          of());
        
        assertMatchesNull("/v1/v2/v3/not-expected");
    }
    
    @Test
    void param_one_declared_first() {
        builder = new RouteBuilder("/").param("param").concat("/blabla");
        
        assertMatches("/value/blabla", of("param", "value"));
        assertMatches("/blabla",       of());
        assertMatches("//blabla",      of());
        
        assertMatchesNull(
                "/value/blabla-no",
                "/value/no-blabla",
                "/value-but-blabla-no",
                "/");
    }
    
    @Test
    void param_two_declared_first() {
        builder = new RouteBuilder("/").param("p1", "p2").concat("/blabla");
        
        assertMatches("/v1/v2/blabla", of("p1", "v1", "p2", "v2"));
        assertMatches("/v1/blabla",    of("p1", "v1"));
        assertMatches("/blabla",       of());
        
        assertMatchesNull(
                "/blabla-no",
                "/");
    }
    
    @Test
    void param_one_middle() {
        builder = new RouteBuilder("/a").param("p").concat("/b");
        
        assertMatches("/a/v/b", of("p", "v"));
        assertMatches("/a/ /b", of("p", " "));
        
        assertMatches("/a/b",   of());
        assertMatches("/a//b",  of());
        
        assertMatchesNull(
                "/v/b",
                "/b",
                "/a/v",
                "/a",
                "/a/v/c/x");
    }
    
    @Test
    void param_one_last() {
        builder = new RouteBuilder("/a").param("p");
        
        assertMatches("/a/v", of("p", "v"));
        assertMatches("/a",   of());
        
        assertMatchesNull(
                "/",
                "/x",
                "/a/v/y");
    }
    
    @Test
    void param_two_last() {
        builder = new RouteBuilder("/a").param("p1", "p2");
        
        assertMatches("/a/v1/v2", of("p1", "v1", "p2", "v2"));
        assertMatches("/a/v1",    of("p1", "v1"));
        assertMatches("/a",       of());
        
        assertMatchesNull(
                "/",
                "/x",
                "/a/v1/v2/x");
    }
    
    @Test
    void mixed() {
        builder = new RouteBuilder("/")
                .param("p1")
                .concat("/a").concat("/b")
                .param("p2", "p3")
                .concat("/c");
        
        assertMatches("/v1/a/b/v2/v3/c", of("p1", "v1", "p2", "v2", "p3", "v3"));
        
        // p1 not provided
        assertMatches("/a/b/v2/v3/c", of("p2", "v2", "p3", "v3"));
        // p3 not provided
        assertMatches("/v1/a/b/v2/c", of("p1", "v1", "p2", "v2"));
        // none provided
        assertMatches("/a/b/c", of());
        
        assertMatchesNull(
                "/v1/x/b/v2/v3/c",
                "/v1/a/x/v2/v3/c",
                "/v1/a/b/v2/v3/x",
                "/",
                "/a",
                "/a/b",
                "/a/b/x");
    }
    
    @Test
    void param_name_empty() {
        builder = new RouteBuilder("/").param("");
        
        assertMatches("/v1", of("", "v1"));
        assertMatches("/",   of());
        
        assertMatchesNull("/x/y");
    }
    
    @Test
    void param_name_blank() {
        builder = new RouteBuilder("/").param(" ");
        
        assertMatches("/v1", of(" ", "v1"));
        assertMatches("/",   of());
        
        assertMatchesNull("/x/y");
    }
    
    @Test
    void param_name_blank_and_empty() {
        builder = new RouteBuilder("/").param(" ", "");
        
        assertMatches("/v1/v2", of(" ", "v1", "", "v2"));
        assertMatches("/v1",    of(" ", "v1"));
        assertMatches("/",      of());
        
        assertMatchesNull("/x/y/z");
    }
    
    @Test
    void param_name_slash() {
        builder = new RouteBuilder("/a").param("/").concat("/c");
        
        assertMatches("/a/v1/c", of("/", "v1"));
        assertMatches("/a/c",    of());
        
        assertMatchesNull("/a/");
    }
    
    @Test
    void uknown_values() {
        builder = new RouteBuilder("/a").param("p1").concat("/b");
        
        assertMatches("/a/v1/b", of("p1", "v1"));
        
        // What if the route matches but we have unknown values?
        // TODO: I think that either "/v2" is actually part of p1's value or
        //       should be considered a segment and therefore not match the Route.
        //       Probably the segment approach is the least magic and most expected?
        assertMatches("/a/v1/v2/b", of("p", "v1"));
    }
    
    // PRIVATE
    // -------
    
    private void assertMatches(String requestTarget, Map<String, String> expectedParameters) {
        if (expectedParameters == null) {
            throw new IllegalArgumentException(
                    "I think you wanted to call assertMatchesNull().");
        }
        
        Route.Match m = testee().matches(requestTarget);
        assertThat(m).isNotNull();
        assertThat(m.route()).isSameAs(testee());
        assertThat(m.parameters()).isEqualTo(expectedParameters);
    }
    
    private void assertMatchesNull(String requestTarget, String... more) {
        Route.Match m = testee().matches(requestTarget);
        assertThat(m).isNull();
        stream(more).forEach(rt -> assertMatchesNull(rt));
    }
    
    private Route testee;
    
    private Route testee() {
        if (testee != null) {
            return testee;
        }
        
        return testee = builder.handler(noop()).build();
    }
}