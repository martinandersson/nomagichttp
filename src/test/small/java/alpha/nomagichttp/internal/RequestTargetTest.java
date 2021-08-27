package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests of {@link RequestTarget}.<p>
 * 
 * This class does testing on the segments (actually provided by {@link
 * SkeletonRequestTarget}), query- and fragment parsing. Path params which are
 * dependent on the resource is tested by {@link DefaultRouteRegistryTest} and
 * {@link DefaultActionRegistryTest} respectively.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RequestTargetTest
{
    private RequestTarget testee;
    
    @Test
    void raw() {
        init("/seg/ment?q=1&q=2#frag");
        expSegRaw("seg", "ment");
        expQryRaw(e("q", "1", "2"));
        expQryDec(e("q", "1", "2"));
        expFragment("frag");
    }
    
    @Test
    void decoded() {
        init("/s%20t?k%20y=v%20l");
        expSegRaw("s%20t");
        expQryRaw(e("k%20y", "v%20l"));
        expQryDec(e("k y", "v l"));
        expFragment();
    }
    
    // Remaining of all these test cases are basically just to bump code coverage
    // (and I seriously don't trust my impl. because I wrote it while being drunk)
    
    @Test
    void empty() {
        init("");
        expSegRaw();
        expQryRaw();
        expQryDec();
        expFragment();
    }
    
    @Test
    void fragment_only_slash_no() {
        init("#");
        expSegRaw();
        expQryRaw();
        expQryDec();
        expFragment();
    }
    
    @Test
    void fragment_only_slash_yes() {
        init("/#");
        expSegRaw();
        expQryRaw();
        expQryDec();
        expFragment();
    }
    
    @Test
    void query_only_slash_no() {
        init("?");
        expSegRaw();
        expQryRaw();
        expQryDec();
        expFragment();
    }
    
    @Test
    void query_only_slash_yes() {
        init("/?");
        expSegRaw();
        expQryRaw();
        expQryDec();
        expFragment();
    }
    
    @Test
    void no_slash() {
        init("X");
        expSegRaw("X");
        expQryRaw();
        expQryDec();
        expFragment();
    }
    
    @Test
    void empty_segments_1() {
        init("///");
        expSegRaw();
        expQryRaw();
        expQryDec();
        expFragment();
    }
    
    @Test
    void empty_segments_2() {
        init("/.//removed/..");
        expSegRaw();
        expQryRaw();
        expQryDec();
        expFragment();
    }
    
    // If ".." has no effect, then it's left as a segment
    // (same as URI, also documented in JavaDoc of Route)
    @Test
    void remove_nothing_1() {
        init("/../");
        expSegRaw("..");
        expQryRaw();
        expQryDec();
        expFragment();
    }
    
    @Test
    void remove_nothing_2() {
        init("/../../");
        expSegRaw("..", "..");
        expQryRaw();
        expQryDec();
        expFragment();
    }
    
    @Test
    void query_no_value_1() {
        init("?a");
        expSegRaw();
        expQryRaw(e("a", ""));
        expQryDec(e("a", ""));
        expFragment();
    }
    
    @Test
    void query_no_value_2() {
        init("?a=&b=");
        expSegRaw();
        expQryRaw(e("a", ""), e("b", ""));
        expQryDec(e("a", ""), e("b", ""));
        expFragment();
    }
    
    @Test
    void query_no_value_3() {
        init("?a&a=");
        expSegRaw();
        expQryRaw(e("a", "", ""));
        expQryDec(e("a", "", ""));
        expFragment();
    }
    
    @Test
    void plus_sign_is_left_alone_1() {
        init("/?+=+");
        expSegRaw();
        expQryRaw(e("+", "+"));
        expQryDec(e("+", "+"));
        expFragment();
    }
    
    @Test
    void plus_sign_is_left_alone_2() {
        init("/?q=+%20+");
        expSegRaw();
        expQryRaw(e("q", "+%20+"));
        expQryDec(e("q", "+ +"));
        expFragment();
    }
    
    private void init(String parse) {
        var skeleton = SkeletonRequestTarget.parse(parse);
        testee = new RequestTarget(skeleton, skeleton.segments());
        assertThat(testee.raw()).isSameAs(parse);
    }
    
    private void expSegRaw(String... values) {
        assertThat(testee.segmentsRaw())
                .containsExactly(values);
    }
    
    @SafeVarargs
    private void expQryRaw(Map.Entry<String, List<String>>... entries) {
        @SuppressWarnings("varargs")
        Map.Entry<String, List<String>>[] m  = entries;
        assertThat(testee.queryMapRaw()).containsExactly(m);
    }
    
    @SafeVarargs
    private void expQryDec(Map.Entry<String, List<String>>... entries) {
        @SuppressWarnings("varargs")
        Map.Entry<String, List<String>>[] m  = entries;
        assertThat(testee.queryMap()).containsExactly(m);
    }
    
    private void expFragment() {
        expFragment("");
    }
    
    private void expFragment(String fragment) {
        assertThat(testee.fragment()).isEqualTo(fragment);
    }
    
    private static Map.Entry<String, List<String>> e(String key, String... values) {
        return Map.entry(key, List.of(values));
    }
}