package alpha.nomagichttp.route;

import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static alpha.nomagichttp.handler.RequestHandlers.GET;
import static alpha.nomagichttp.message.Responses.accepted;
import static alpha.nomagichttp.util.PercentDecoder.decode;
import static java.util.Arrays.stream;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Small tests for {@link DefaultRouteRegistry}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class DefaultRouteRegistryTest
{
    private static final Route ROOT_NOOP = dummyRoute("/");
    
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
        Route r = dummyRoute("/child-of-root");
        testee.add(r);
        assertMatch("/child-of-root", r);
    }
    
    @Test
    void can_add_child_level_2() {
        testee.add(dummyRoute("/a"));
        Route r = dummyRoute("/a/b");
        testee.add(r);
        assertMatch("/a/b", r);
    }
    
    // We should be able to add "/" even if "/a" is registered
    @Test
    void can_add_parent_level_1() {
        testee.add(dummyRoute("/a"));
        testee.add(ROOT_NOOP);
        assertMatch("/", ROOT_NOOP);
    }
    
    @Test
    void can_add_parent_level_2() {
        testee.add(dummyRoute("/a/b"));
        Route r = dummyRoute("/a");
        testee.add(r);
        assertMatch("/a", r);
    }
    
    // We should be able to add "/b" even if "/a" is registered
    @Test
    void can_add_sibling() {
        testee.add(dummyRoute("/a"));
        Route r = dummyRoute("/b");
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
            reg.add(dummyRoute(e[0]));
            assertThatThrownBy(() -> reg.add(dummyRoute(e[1])))
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
        Route exp = dummyRoute("/a/b/c");
        testee.add(exp);
        
        String[] salt = {
                "/",
                "/a",
                "/a/b",
                "/a/b/sibling" };
        
        Stream.of(salt).forEach(p -> testee.add(dummyRoute(p)));
        
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
        Route r = dummyRoute("/:p");
        testee.add(r);
        
        assertMatch(
                "/v", r, Map.of("p", "v"));
        assertNoMatch(
                "/",
                "/v/saturated");
    }
    
    @Test
    void path_param_single_branch_shared() {
        Route r1 = dummyRoute("/:p1"),
              r2 = dummyRoute("/:p1/:p2"),
              r3 = dummyRoute("/:p1/:p2/segment");
        
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
        Route r = dummyRoute("/src/*p");
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
    
    // Remove
    // ----
    
    @Test
    void remove_reference() {
        Route r = dummyRoute("/download/:user/*filepath");
        testee.add(r);
        assertThat(testee.remove(r)).isTrue();
        assertThat(dump()).containsOnlyKeys("/");
    }
    
    @Test
    void remove_pattern_names_yes() {
        Route r = dummyRoute("/download/:user/*filepath");
        testee.add(r);
        assertThat(testee.remove("/download/:user/*filepath")).isSameAs(r);
        assertThat(dump()).containsOnlyKeys("/");
    }
    
    @Test
    void remove_pattern_names_no() {
        Route r = dummyRoute("/download/:user/*filepath");
        testee.add(r);
        assertThat(testee.remove("/download/:/*")).isSameAs(r); // <-- empty
        assertThat(dump()).containsOnlyKeys("/");
    }
    
    @Test
    void remove_pattern_names_duplicated() {
        Route r = dummyRoute("/download/:user/*filepath");
        testee.add(r);
        assertThat(testee.remove("/download/:bla/*bla")).isSameAs(r); // <-- duplicated
        assertThat(dump()).containsOnlyKeys("/");
    }
    
    // Bug fix
    // ----
    
    /**
     * 2021-01-24:
     * Old implementation of Tree.walk() was unreleasing nodes only as they were
     * explicitly returned from the digger. This put a requirement on the digger
     * to only dig one level at a time, returning all child nodes it created to
     * the walk() method.
     * 
     * The DefaultRouteRegistry.add() however may create (and implicitly
     * reserve) a catch-all child without ever returning the child node from the
     * digger. The consequence was that the newly minted node was never
     * unreleased and therefore never subject to be pruned off of the tree.
     * 
     * Instead of changing the registry implementation, the fix was to remove
     * the requirement from the Tree implementation. Each parent node will now
     * automagically add the child node to a thread local deque of reserved
     * nodes which is polled and unreleased by the walk() method before
     * returning. This gives the digger a complete freedom to dig the tree
     * however it pleases without any gotchas.
     */
    @Test
    void bug_catch_all_child_not_unreserved() {
        Route r = dummyRoute("*p");
        
        testee.add(r);
        assertThat(dump()).containsExactly(
                // two nodes in the tree; the parent root "/" (value null) and the child "*" (value route)
                entry("/", null), entry("/*", r));
        
        assertThat(testee.remove(r)).isTrue();
        assertThat(dump()).containsExactly(
                // only root! (before fix the map also had the "*" node in it, albeit with a null value)
                entry("/", null));
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
    
    private static Route dummyRoute(String pattern) {
        return Route.builder(pattern)
                .handler(GET().apply(requestIgnored -> accepted().completedStage()))
                .build();
    }
    
    private static Iterable<String> toSegments(String path) {
        return stream(path.split("/"))
                .filter(not(String::isEmpty))
                .collect(toList());
    }
    
    private Map<String, Route> dump() {
        return ((DefaultRouteRegistry) testee).dump();
    }
    
    /**
     * Returns a {@code Map.Entry} that is immutable and allow null values,
     *
     * @param k key
     * @param v value
     * @param <K> type of key
     * @param <V> type of value
     *
     * @return a map entry
     */
    // TODO: Copy-pasted from Tree, DRY
    private static <K, V> Map.Entry<K, V> entry(K k, V v) {
        return new AbstractMap.SimpleImmutableEntry<>(k, v);
    }
}