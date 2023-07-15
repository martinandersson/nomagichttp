package alpha.nomagichttp.util;

import alpha.nomagichttp.route.Route;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableList;

/**
 * Util for percent-decoding.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Route
 */
public final class PercentDecoder
{
    private PercentDecoder() {
        // Empty
    }
    
    /**
     * Percent-decode the given string.<p>
     * 
     * Adheres to the contract specified in {@link Route}.
     * 
     * @param str string to decode (non-null)
     * @return a decoded string
     * @throws NullPointerException if {@code str} is {@code null}
     */
    public static String decode(String str) {
        final int p = str.indexOf('+');
        if (p == -1) {
            // No plus characters? JDK-decode the entire string
            return URLDecoder.decode(str, UTF_8);
        } else {
            // Else decode chunks in-between
            return decode(str.substring(0, p)) + "+" + decode(str.substring(p + 1));
        }
    }
    
    /**
     * Percent decode all given strings.<p>
     * 
     * Adheres to the contract specified in {@link Route}.<p>
     * 
     * The returned list implements RandomAccess and is unmodifiable.
     *
     * @param strings to decode 
     * @return the result
     * 
     * @throws NullPointerException
     *             if {@code strings} or any element thereof is {@code null}
     */
    public static List<String> decode(Iterable<String> strings) {
        List<String> l = new ArrayList<>();
        strings.forEach(s -> l.add(decode(s)));
        return unmodifiableList(l);
    }
}
