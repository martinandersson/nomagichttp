package alpha.nomagichttp.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small tests of {@link RequestTarget}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class RequestTargetTest
{
    private RequestTarget testee;
    
    @Test
    void raw() {
        init("/seg/ment?q=1&q=2#ignored");
        expSegRaw("seg", "ment");
        expSegDec("seg", "ment");
        expQryRaw(e("q", "1", "2"));
        expQryDec(e("q", "1", "2"));
    }
    
    @Test
    void decoded() {
        init("/s%20t?k%20y=v%20l");
        expSegRaw("s%20t");
        expSegDec("s t");
        expQryRaw(e("k%20y", "v%20l"));
        expQryDec(e("k y", "v l"));
    }
    
    // Remaining of all these test cases are basically just to bump code coverage
    // (and I seriously don't trust my impl. because I wrote it real fast while being drunk)
    
    @Test
    void empty() {
        init("");
        expSegRaw();
        expSegDec();
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void fragment_only_slash_no() {
        init("#");
        expSegRaw();
        expSegDec();
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void fragment_only_slash_yes() {
        init("/#");
        expSegRaw();
        expSegDec();
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void query_only_slash_no() {
        init("?");
        expSegRaw();
        expSegDec();
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void query_only_slash_yes() {
        init("/?");
        expSegRaw();
        expSegDec();
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void no_slash() {
        init("X");
        expSegRaw("X");
        expSegDec("X");
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void empty_segments_1() {
        init("///");
        expSegRaw();
        expSegDec();
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void empty_segments_2() {
        init("/.//removed/..");
        expSegRaw();
        expSegDec();
        expQryRaw();
        expQryDec();
    }
    
    // If ".." has no effect, then it's left as a segment
    // (same as URI, also documented in Javadoc of Route)
    @Test
    void remove_nothing_1() {
        init("/../");
        expSegRaw("..");
        expSegDec("..");
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void remove_nothing_2() {
        init("/../../");
        expSegRaw("..", "..");
        expSegDec("..", "..");
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void query_no_value_1() {
        init("?a");
        expSegRaw();
        expSegDec();
        expQryRaw(e("a", ""));
        expQryDec(e("a", ""));
    }
    
    @Test
    void query_no_value_2() {
        init("?a=&b=");
        expSegRaw();
        expSegDec();
        expQryRaw(e("a", ""), e("b", ""));
        expQryDec(e("a", ""), e("b", ""));
    }
    
    @Test
    void query_no_value_3() {
        init("?a&a=");
        expSegRaw();
        expSegDec();
        expQryRaw(e("a", "", ""));
        expQryDec(e("a", "", ""));
    }
    
    @Test
    void plus_sign_is_left_alone_1() {
        init("/+");
        expSegRaw("+");
        expSegDec("+");
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void plus_sign_is_left_alone_2() {
        init("/+%20+");
        expSegRaw("+%20+");
        expSegDec("+ +");
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void plus_sign_is_left_alone_3() {
        init("/++");
        expSegRaw("++");
        expSegDec("++");
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void plus_sign_is_left_alone_4() {
        init("/+++");
        expSegRaw("+++");
        expSegDec("+++");
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void plus_sign_is_left_alone_5() {
        init("/a+");
        expSegRaw("a+");
        expSegDec("a+");
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void plus_sign_is_left_alone_6() {
        init("/+a");
        expSegRaw("+a");
        expSegDec("+a");
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void plus_sign_is_left_alone_7() {
        init("/a+b");
        expSegRaw("a+b");
        expSegDec("a+b");
        expQryRaw();
        expQryDec();
    }
    
    @Test
    void plus_sign_is_left_alone_8() {
        init("/%20a%20+%20b%20");
        expSegRaw("%20a%20+%20b%20");
        expSegDec(" a + b ");
        expQryRaw();
        expQryDec();
    }
    
    
    private void init(String parse) {
        testee = RequestTarget.parse(parse);
        assertThat(testee.pathRaw()).isSameAs(parse);
    }
    
    private void expSegRaw(String... values) {
        assertThat(testee.segmentsNotPercentDecoded())
                .containsExactly(values);
    }
    
    private void expSegDec(String... values) {
        assertThat(testee.segmentsPercentDecoded())
                .containsExactly(values);
    }
    
    @SafeVarargs
    private void expQryRaw(Map.Entry<String, List<String>>... entries) {
        assertThat(testee.queryMapNotPercentDecoded())
                .containsExactly(entries);
    }
    
    @SafeVarargs
    private void expQryDec(Map.Entry<String, List<String>>... entries) {
        assertThat(testee.queryMapPercentDecoded())
                .containsExactly(entries);
    }
    
    private static Map.Entry<String, List<String>> e(String key, String... values) {
        return Map.entry(key, List.of(values));
    }
}