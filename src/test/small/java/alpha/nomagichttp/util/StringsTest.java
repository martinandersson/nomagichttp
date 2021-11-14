package alpha.nomagichttp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for utility class {@code Strings}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
class StringsTest
{
    @Test
    void split() {
        // JavaDoc example
        given("one.two", '.', '"');
        expect("one", "two");
        
        // JavaDoc example
        given("one.\"keep.this\"", '.', '"');
        expect("one", "\"keep.this\"");
        
        // JavaDoc example
        given("...", '.', '"');
        expect();
        
        // JavaDoc example
        given("one.\"t\\\"w.o\"", '.', '"');
        expect("one", "\"t\\\"w.o\"");
        
        // JavaDoc example
        given("one\\.two", '.', '"');
        expect("one\\", "two");
        
        // Splitting a media type
        given("this/that;param=value", ';', '"');
        expect("this/that", "param=value");
        
        // Delimiter and exclude char are the same 
        given("", ' ', ' ');
        assertThatThrownBy(() -> expect("dead assertion"))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        
        // No split and no exclusion
        given("abc", '.', '"');
        expect("abc");
        
        // Exclusion boundary is not closed
        given("one\".two", '.', '"');
        expect("one\".two");
        
        // No empty tokens
        given(",x,", ',', '"');
        expect("x");
        
        given(",x,,", ',', '"');
        expect("x");
    }
    
    @Test
    void unquote() {
        // All examples from Strings.unquote JavaDoc, in order
        String[][] cases = {
            {"no\\\"effect", "no\\\"effect"},
            {"\"one\"", "one"},
            {"\"one\\\"two\\\"\"", "one\"two\""},
            {"\"one\\\\\"two\"", "one\\\"two"},
            {"\"one\\\\\\\"two\"", "one\\\"two"},
            {"\"one\\\\\\\\\"two\"", "one\\\\\"two"} };
        
        for (String[] c : cases) {
            assertThat(Strings.unquote(c[0])).isEqualTo(c[1]);
        }
    }
    
    @Test
    void containsIgnoreCase() {
        assertTrue(Strings.containsIgnoreCase("", ""));
        assertTrue(Strings.containsIgnoreCase("abc", ""));
        assertTrue(Strings.containsIgnoreCase("abc", "b"));
        assertFalse(Strings.containsIgnoreCase("abc", "z"));
        assertTrue(Strings.containsIgnoreCase("cAsE", "CaSe"));
    }
    
    String testee;
    char delimiter;
    char excludeBoundary;
    
    private void given(String toSplit, char delimiter, char excludeBoundary) {
        this.testee = toSplit;
        this.delimiter = delimiter;
        this.excludeBoundary = excludeBoundary;
    }
    
    private void expect(String... expected) {
        assertThat(Strings.split(testee, delimiter, excludeBoundary))
                .isEqualTo(expected);
    }
}