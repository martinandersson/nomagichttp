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
        given("a,b,c", ',', '_');
        expect("a", "b", "c");
        
        given("this/that;param=value", ';', '_');
        expect("this/that", "param=value");
        
        given("this/that;param=\"v;a;l;u;e\"", ';', '"');
        expect("this/that", "param=\"v;a;l;u;e\"");
        
        given("a;q=0, b;p=\"my,val\",p=y,z", ',', '"');
        expect("a;q=0", " b;p=\"my,val\"", "p=y", "z");
        
        
        given("", ' ', ' ');
        assertThatThrownBy(() -> expect(""))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        
        given(" ", ' ', '_');
        expect();
        
        given(",x,", ',', '_');
        expect("x");
        
        given(",x,,", ',', '_');
        expect("x");
        
        given("...", '_', '.');
        expect();
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
    
    @Test
    void containsIgnoreCase() {
        assertTrue(Strings.containsIgnoreCase("", ""));
        assertTrue(Strings.containsIgnoreCase("abc", ""));
        assertTrue(Strings.containsIgnoreCase("abc", "b"));
        assertFalse(Strings.containsIgnoreCase("abc", "z"));
        assertTrue(Strings.containsIgnoreCase("cAsE", "CaSe"));
    }
}