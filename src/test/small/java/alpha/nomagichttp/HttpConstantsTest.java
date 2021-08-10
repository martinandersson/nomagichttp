package alpha.nomagichttp;

import alpha.nomagichttp.util.CodeAndPhraseCache;
import alpha.nomagichttp.testutil.TestConstants;
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
import static java.text.MessageFormat.format;
import static java.util.Arrays.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
class HttpConstantsTest
{
    @Test
    void statusCode_constants_eq_values() throws IllegalAccessException {
        assertEquals(
            // All constants ...
            getConstants(StatusCode.class, int.class),
            // Must be in VALUES.
            toSet(StatusCode.VALUES));
    }
    
    @Test
    void reasonPhrase_constants_eq_values() throws IllegalAccessException {
        var phrases = getConstants(ReasonPhrase.class, String.class);
        phrases.remove(UNKNOWN);
        phrases.remove(PAYLOAD_TOO_LARGE);
        assertEquals(phrases, toSet(ReasonPhrase.VALUES));
    }
    
    @Test
    void valuesSameSize() {
        assertEquals(StatusCode.VALUES.length, ReasonPhrase.VALUES.length);
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