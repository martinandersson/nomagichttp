package alpha.nomagichttp;

import alpha.nomagichttp.message.HttpVersionParseException;
import alpha.nomagichttp.testutil.TestConstants;
import alpha.nomagichttp.util.CodeAndPhraseCache;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static alpha.nomagichttp.HttpConstants.ReasonPhrase;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.PAYLOAD_TOO_LARGE;
import static alpha.nomagichttp.HttpConstants.ReasonPhrase.UNKNOWN;
import static alpha.nomagichttp.HttpConstants.StatusCode;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_0_9;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_0;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_1_1;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_2;
import static alpha.nomagichttp.HttpConstants.Version.HTTP_3;
import static alpha.nomagichttp.HttpConstants.Version.parse;
import static java.text.MessageFormat.format;
import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small tests for {@link HttpConstants}.<p>
 * 
 * The point of these tests is to ensure that future modifications to the status
 * code- and reason phrase constants are also reflected in the respective
 * "#VALUES" array in the classes and that the array elements are declared in
 * the same order such that the final status line can be assembled
 * programmatically and confidentially by iterating one and using that
 * cursor/index for retrieving the counterpart value from the other.<p>
 * 
 * One could ask why didn't we just introduce a complex StatusLine object from
 * the start or add methods such as StatusCode.forPhrase() and
 * ReasonPhrase.forCode()?<p>
 * 
 * Well, primarily because the two really are two distinct things with two
 * different purposes (code for machine and phrase for human), the latter having
 * always been optional - so there's really only a status code. HTTP/2 even
 * dropped the reason phrase altogether, not used anymore.<p>
 * 
 * Without a complex object, the API (such as creating a Response object) is
 * simpler, much simpler. Further, it's absolutely not expected that the
 * application will have a need to traverse from one to the other. The status
 * lines will most likely be "hardcoded", e.g. "Responses.status(200, "OK")"
 * which is super readable and straight forward.<p>
 * 
 * Any need to programmatically access either one would also have the need to
 * discover the constants, i.e. some form of enumeration, so a couple of util
 * methods wouldn't have been enough. Both discovery and the mapping between the
 * two is now fully covered by the arrays, which from a programmatic point of
 * view, is actually a pretty easy API to work with - and fast!<p>
 * 
 * These arrays were originally put in place due to internal needs (see
 * {@link CodeAndPhraseCache}).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class HttpConstantsTest
{
    @Nested
    class OfStatusCode {
        @Test
        void constants_eq_values() throws IllegalAccessException {
            assertEquals(
                // All constants ...
                getConstants(StatusCode.class, int.class),
                // Must be in VALUES.
                toSet(StatusCode.VALUES));
        }
        
        @Test
        void codesAscending() {
            int prev = Integer.MIN_VALUE;
            for (int check : StatusCode.VALUES) {
                assertThat(check).isGreaterThan(prev);
                prev = check;
            }
        }
        
        @Test
        void correspondingOrder() {
            var exp = TestConstants.statusLines();
            var act = new ArrayList<>();
            for (int i = 0; i < StatusCode.VALUES.length; ++i) {
                act.add(format("{0} ({1})",
                    StatusCode.VALUES[i], ReasonPhrase.VALUES[i]));
            }
            assertEquals(exp, act);
        }
    }
    
    @Nested
    class OfReasonPhrase {
        @Test
        void constants_eq_values() throws IllegalAccessException {
            var phrases = getConstants(ReasonPhrase.class, String.class);
            phrases.remove(UNKNOWN);
            phrases.remove(PAYLOAD_TOO_LARGE);
            assertEquals(phrases, toSet(ReasonPhrase.VALUES));
        }
    }
    
    @Test
    void valuesSameSize() {
        assertEquals(StatusCode.VALUES.length, ReasonPhrase.VALUES.length);
    }
    
    @Nested
    class OfVersion {
        @Test
        void lessThan() {
            assertThat(HTTP_1_0.isLessThan(HTTP_1_1)).isTrue();
        }
        
        @Test
        void greaterThan() {
            assertThat(HTTP_1_1.isGreaterThan(HTTP_1_0)).isTrue();
        }
        
        // The rest is parsing tests
        
        @Test
        void happy_path() {
            Object[][] cases = {
                {"HTTP/0.9", HTTP_0_9},
                {"HTTP/1.0", HTTP_1_0},
                {"HTTP/1.1", HTTP_1_1},
                {"HTTP/2",   HTTP_2   },
                {"HTTP/3",   HTTP_3   } };
            
            stream(cases).forEach(v -> {
                assertThat(parse((String) v[0])).isSameAs(v[1]);
                assertThat(v[1].toString()).isEqualTo(v[0]);
            });
        }
        
        @Test
        void exc_noSlash() {
            assertThatThrownBy(() -> parse(""))
                    .isExactlyInstanceOf(HttpVersionParseException.class)
                    .hasNoCause()
                    .hasMessage("No forward slash.")
                    .extracting("requestFieldValue")
                    .isEqualTo("");
        }
        
        @Test
        void exc_noHTTP() {
            assertThatThrownBy(() -> parse("hTtP/"))
                    .isExactlyInstanceOf(HttpVersionParseException.class)
                    .hasNoCause()
                    .hasMessage("HTTP-name \"hTtP\" is not \"HTTP\".")
                    .extracting("requestFieldValue")
                    .isEqualTo("hTtP/");
        }
        
        @Test
        void exc_parseMajorFail() {
            assertThatThrownBy(() -> parse("HTTP/x"))
                    .isExactlyInstanceOf(HttpVersionParseException.class)
                    .hasCauseExactlyInstanceOf(NumberFormatException.class)
                    .extracting("requestFieldValue")
                    .isEqualTo("HTTP/x");
        }
        
        @Test
        void exc_parseMinorFail() {
            assertThatThrownBy(() -> parse("HTTP/1.x"))
                    .isExactlyInstanceOf(HttpVersionParseException.class)
                    .hasCauseExactlyInstanceOf(NumberFormatException.class)
                    .extracting("requestFieldValue")
                    .isEqualTo("HTTP/1.x");
        }
        
        @Test
        void exc_minorNotSupported_major0() {
            assertThatThrownBy(() -> parse("HTTP/0.8"))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasNoCause()
                    .hasMessage("0:8");
        }
        
        @Test
        void exc_minorNotSupported_major1() {
            assertThatThrownBy(() -> parse("HTTP/1.3"))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasNoCause()
                    .hasMessage("1:3");
        }
        
        @Test
        void exc_majorNotSupported() {
            assertThatThrownBy(() -> parse("HTTP/99999999"))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasNoCause()
                    .hasMessage("99999999:");
        }
        
        @Test
        void exc_minorRequired() {
            assertThatThrownBy(() -> parse("HTTP/1"))
                    .isExactlyInstanceOf(HttpVersionParseException.class)
                    .hasNoCause()
                    .hasMessage("No minor version provided when one was expected.")
                    .extracting("requestFieldValue")
                    .isEqualTo("HTTP/1");
        }
        
        @Test
        void exc_minorUnexpected_dot() {
            assertThatThrownBy(() -> parse("HTTP/2."))
                    .isExactlyInstanceOf(HttpVersionParseException.class)
                    .hasNoCause()
                    .hasMessage("Minor version provided when none was expected.")
                    .extracting("requestFieldValue")
                    .isEqualTo("HTTP/2.");
        }
        
        @Test
        void exc_minorUnexpected_number() {
            assertThatThrownBy(() -> parse("HTTP/2.1"))
                    .isExactlyInstanceOf(HttpVersionParseException.class)
                    .hasNoCause()
                    .hasMessage("Minor version provided when none was expected.")
                    .extracting("requestFieldValue")
                    .isEqualTo("HTTP/2.1");
        }
        
        @Test
        void isLessThan() {
            HttpConstants.Version[] v = HttpConstants.Version.values();
            for (int i = 0; i < v.length - 1; ++i) {
                assertTrue(v[i].isLessThan(v[i + 1])); // HTTP 0.9 < 1.0
                assertFalse(v[i].isLessThan(v[i]));    // !(HTTP 0.9 < HTTP 0.9)
            }
        }
    }
    
    private static <V> Set<V> getConstants(Class<?> namespace, Class<V> type)
            throws IllegalAccessException
    {
        Set<V> vals = new HashSet<>();
        for (Field f : namespace.getDeclaredFields()) {
            if (f.getType().equals(type)) {
                @SuppressWarnings("unchecked")
                V v = (V) f.get(null);
                vals.add(v);
            }
        }
        return vals;
    }
    
    private static Set<Integer> toSet(int[] arr) {
        return toSet(stream(arr).boxed());
    }
    
    private static <T> Set<T> toSet(T[] arr) {
        return toSet(stream(arr));
    }
    
    private static <T> Set<T> toSet(Stream<? extends T> stream) {
        return stream.collect(Collectors.toSet());
    }
}