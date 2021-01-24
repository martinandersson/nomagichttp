package alpha.nomagichttp.internal;


import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteCollisionException;
import alpha.nomagichttp.route.RouteRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static alpha.nomagichttp.handler.RequestHandlers.noop;
import static alpha.nomagichttp.internal.PercentDecoder.decode;
import static alpha.nomagichttp.route.Routes.route;
import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link DefaultRouteRegistry}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultRouteRegistryTest
{
    private static final Route ROOT_NOOP = route("/", noop());
    
    private final RouteRegistry testee = new DefaultRouteRegistry();
    
    // Simple match cases
    // ----
    
    @Test
    void match_root() {
        testee.add(ROOT_NOOP);
        assertMatch("/", ROOT_NOOP);
        assertNoMatch("/blabla");
    }
    
    // We should be able to add "/a" even if "/" is registered
    @Test
    void can_add_child_level_1() {
        testee.add(ROOT_NOOP);
        Route r = route("/child-of-root", noop());
        testee.add(r);
        assertMatch("/child-of-root", r);
    }
    
    @Test
    void can_add_child_level_2() {
        testee.add(route("/a", noop()));
        Route r = route("/a/b", noop());
        testee.add(r);
        assertMatch("/a/b", r);
    }
    
    // We should be able to add "/" even if "/a" is registered
    @Test
    void can_add_parent_level_1() {
        testee.add(route("/a", noop()));
        testee.add(ROOT_NOOP);
        assertMatch("/", ROOT_NOOP);
    }
    
    @Test
    void can_add_parent_level_2() {
        testee.add(route("/a/b", noop()));
        Route r = route("/a", noop());
        testee.add(r);
        assertMatch("/a", r);
    }
    
    // We should be able to add "/b" even if "/a" is registered
    @Test
    void can_add_sibling() {
        testee.add(route("/a", noop()));
        Route r = route("/b", noop());
        testee.add(r);
        assertMatch("/b", r);
    }
    
    // Throwables
    // ----
    
    @Test
    void empty_segment() {
        assertThatThrownBy(() -> testee.lookup(List.of("")))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Segment value is empty.");
    }
    
    @Test
    void route_collisions() {
        Stream.of(
            // First add a route, then add another route, expect crash with message
            e("/",     "/",     "Route \"/\" is equivalent to an already added route \"/\"."),
            e("/:p1",  "/:p2",  "Route \"/:p2\" is equivalent to an already added route \"/:p1\"."),
            e("/:p1",  "/xxx",  "Hierarchical position of \"xxx\" is occupied with non-compatible type."),
            e("/xxx",  "/:p1",  "Hierarchical position of \":p1\" is occupied with non-compatible type."),
            e("/",     "/*p1",  "Route \"/*p1\" is equivalent to an already added route \"/\"."),
            e("/xxx",  "/*p1",  "Hierarchical position of \"*p1\" is occupied with non-compatible type."),
            e("/*p1",  "/*p2",  "Route \"/*p2\" is equivalent to an already added route \"/*p1\".")
        ).forEach(e -> {
            RouteRegistry reg = new DefaultRouteRegistry();
            reg.add(route(e[0], noop()));
            assertThatThrownBy(() -> reg.add(route(e[1], noop())))
                    .isExactlyInstanceOf(RouteCollisionException.class)
                    .hasMessage(e[2]);
        });
    }
    
    private static String[] e(String... strings) {
        return strings;
    }
    
    // More advanced match cases
    // ----
    
    @Test
    void match_abc() {
        Route exp = route("/a/b/c", noop());
        testee.add(exp);
        
        String[] salt = {
                "/",
                "/a",
                "/a/b",
                "/a/b/sibling" };
        
        Stream.of(salt).forEach(p -> testee.add(route(p, noop())));
        
        assertMatch(
                "/a/b/c", exp);
        assertNoMatch(
                "/x/b/c",
                "/a/x/c",
                "/a/b/x",
                "/x/a/b/c",
                "/a/b/c/x");
    }
    
    @Test
    void path_param_single_singleton() {
        Route r = route("/:p", noop());
        testee.add(r);
        
        assertMatch(
                "/v", r, Map.of("p", "v"));
        assertNoMatch(
                "/",
                "/v/saturated");
    }
    
    @Test
    void path_param_single_branch_shared() {
        Route r1 = route("/:p1", noop()),
              r2 = route("/:p1/:p2", noop()),
              r3 = route("/:p1/:p2/segment", noop());
        
        testee.add(r1);
        testee.add(r2);
        testee.add(r3);
        
        assertMatch(
                "/v1",             r1,  Map.of("p1", "v1"),
                "/v1/v2",          r2,  Map.of("p1", "v1", "p2", "v2"),
                "/v1/v2/segment",  r3,  Map.of("p1", "v1", "p2", "v2"));
        assertNoMatch(
                "/",
                "/v/v/wrong");
    }
    
    @Test
    void path_param_catchAll() {
        Route r = route("/src/*p", noop());
        testee.add(r);
        
        assertMatch(
                "/src",            r, Map.of("p", "/"),
                "/src/",           r, Map.of("p", "/"),
                "/src/a",          r, Map.of("p", "/a"),
                "/src/a/b",        r, Map.of("p", "/a/b"),
                "/src///a///b///", r, Map.of("p", "/a/b"), // <-- the entire path is normalized
                "/src/a%20b",      r, Map.of("p", "/a%20b"));
        assertNoMatch(
                "/",
                "xxx");
    }
    
    // Private API
    // ----
    
    private void assertMatch(String path, Route expectSame) {
        assertMatch(path, expectSame, Map.of());
    }
    
    private void assertMatch(
            String path,
            Route expectSame,
            Map<String, String> expectedParamValuesRaw,
            Object... repeatedCases)
    {
        RouteRegistry.Match m = testee.lookup(toSegments(path));
        assertThat(m.route()).isSameAs(expectSame);
        
        DefaultRouteRegistry.DefaultMatch df = (DefaultRouteRegistry.DefaultMatch) m;
        assertThat(df.mapRaw()).isEqualTo(expectedParamValuesRaw);
        
        Map<String, String> decoded = new HashMap<>(expectedParamValuesRaw);
        decoded.replaceAll((k, v) -> decode(v));
        assertThat(df.mapDec()).isEqualTo(decoded);
        
        for (int arg1 = 0, arg2 = 1, arg3 = 2; arg3 < repeatedCases.length; arg1 += 3, arg2 += 3, arg3 += 3) {
            @SuppressWarnings({"unchecked"})
            Map<String, String> map = (Map<String, String>) repeatedCases[arg3];
            assertMatch((String) repeatedCases[arg1], (Route) repeatedCases[arg2], map);
        }
    }
    
    private void assertNoMatch(String... paths) {
        stream(paths).forEach(p ->
            assertThatThrownBy(() -> testee.lookup(toSegments(p)))
                .isExactlyInstanceOf(NoRouteFoundException.class)
                .hasMessage(null));
    }
    
    private static Iterable<String> toSegments(String path) {
        return RequestTarget.parse(path).segmentsNotPercentDecoded();
    }
}