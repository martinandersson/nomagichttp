package alpha.nomagichttp.util;

import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.stream.Stream;

import static java.lang.Character.MIN_VALUE;
import static java.util.Objects.requireNonNull;

/**
 * String utilities.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Strings
{
    private Strings() {
        // Empty
    }
    
    /**
     * Split a string into a returned stream.<p>
     * 
     * Works just as {@code String.split}, except this method never returns
     * empty substrings.
     * 
     * @param str to split
     * @param delimiter to split by
     * 
     * @return the substrings
     * @throws NullPointerException if {@code str} is {@code null}
     */
    public static Stream<String> split(CharSequence str, char delimiter) {
        var b = Stream.<String>builder();
        splitToSink(str, delimiter, b);
        return b.build();
    }
    
    /**
     * Split a string into a given sink.<p>
     * 
     * Works just as {@code String.split}, except this method never returns
     * empty substrings.
     * 
     * @param str to split
     * @param delimiter to split by
     * @param sink of substrings
     * 
     * @throws NullPointerException
     *             if {@code str} or {@code sink} is {@code null}
     */
    public static void splitToSink(
            CharSequence str, char delimiter, Consumer<String> sink) {
        split0(str, delimiter, c -> false, c -> false, sink);
    }
    
    /**
     * Split a string into a returned stream.<p>
     * 
     * Works just as {@code String.split}, except this method respects exclusion
     * zones within which, the delimiter will have no effect. Also, this method
     * never returns empty substrings.<p>
     * 
     * For example, good to use when substrings may be quoted and no split
     * should occur within the quoted parts.
     * 
     * <pre>
     *   split("one.two", '.', '"') returns "one", "two"
     *   split("one.\"keep.this\"", '.', '"') returns "one", ""keep.this""
     *   split("...", '.', '"') returns an empty Stream
     * </pre>
     * 
     * Note how the quoted part is kept intact. It can be unquoted using {@link
     * #unquote(String)}<p>
     * 
     * An immediately preceding backslash character is interpreted as escaping
     * the delimiter, but only within exclusion zones. Just as with the
     * delimiter character, the escaping backslash too is kept.
     * <pre>
     *   split("one.\"t\\\"w.o\"", '.', '"') returns "one", ""t\"w.o""
     * </pre>
     * 
     * Outside an exclusion zone, the backslash character is just like any other
     * character.
     * <pre>
     *   split("one\\.two", '.', '"') returns "one\", "two"
     * </pre>
     * 
     * @param str to split
     * @param delimiter to split by (if not excluded)
     * @param excludeBoundary defines the exclusion zone
     * 
     * @return the substrings
     * 
     * @throws NullPointerException
     *            if {@code str} is {@code null}
     * 
     * @throws IllegalArgumentException
     *             if {@code delimiter} is the backslash character, or
     *             if {@code delimiter} and {@code excludeBoundary} are the same
     * 
     * @see #unquote(String) 
     */
    public static Stream<String> split(
            CharSequence str, char delimiter, char excludeBoundary) {
        var b = Stream.<String>builder();
        splitToSink(str, delimiter, excludeBoundary, b);
        return b.build();
    }
    
    /**
     * Split a string into tokens put in a sink.<p>
     * 
     * Works just as {@link #split(CharSequence, char, char)}, except pushes all
     * substrings to the given sink instead of a returned stream.
     * 
     * @param str to split
     * @param delimiter to split by (if not excluded)
     * @param excludeBoundary defines the exclusion zone
     * @param sink of substrings
     * 
     * @throws NullPointerException
     *            if {@code str} or {@code sink} is {@code null}
     * 
     * @throws IllegalArgumentException
     *             if {@code delimiter} is the backslash character, or
     *             if {@code delimiter} and {@code excludeBoundary} are the same
     */
    public static void splitToSink(
            CharSequence str, char delimiter, char excludeBoundary,
            Consumer<String> sink)
    {
        if (delimiter == '\\') {
            throw new IllegalArgumentException(
                    "Delimiter char can not be the escape char.");
        }
        if (delimiter == excludeBoundary) {
            throw new IllegalArgumentException(
                    "Delimiter char can not be the same as exclude char.");
        }
        split0(str, delimiter, c -> c == '\\', c -> c == excludeBoundary, sink);
    }
    
    private static void split0(
            CharSequence str, char delimiter,
            IntPredicate escapeChar, IntPredicate excludeChar,
            Consumer<String> sink)
    {
        requireNonNull(sink);
        StringBuilder tkn = null;
        
        final int len = str.length();
        char prev = MIN_VALUE;
        boolean excluding = false;
        
        for (int i = 0; i < len; ++i) {
            final char c = str.charAt(i);
            boolean split;
            
            if (escapeChar.test(prev) && excluding) {
                // Whatever c is, keep building current token
                split = false;
            } else if (c == delimiter) {
                // We split only if we're not excluding
                split = !excluding;
            } else if (excludeChar.test(c)) {
                // Certainly not cause for split
                split = false;
                // But does toggle the current mode
                excluding = !excluding;
            } else {
                // Anything else has no meaning; face value
                split = false;
            }
            
            prev = c;
            
            if (split) {
                // Done, begin new token
                pushNullable(tkn, sink);
                tkn = null;
            } else {
                // Add c to current token
                if (tkn == null) {
                    tkn = new StringBuilder();
                }
                tkn.append(c);
            }
        }
        
        pushNullable(tkn, sink);
    }
    
    private static void pushNullable(StringBuilder sb, Consumer<String> sink) {
        if (sb != null) {
            var s = sb.toString();
            assert !s.isEmpty();
            sink.accept(s);
        }
    }
    
    /**
     * Unquote a quoted string.<p>
     * 
     * Actions performed, in order:
     * <ul>
     *   <li>Remove at most one leading and trailing '"' character</li>
     *   <li>If no effect, return original string</li>
     *   <li>Otherwise return {@link String#translateEscapes()
     *           translateEscapes}().{@link String#strip() strip}()</li>
     * </ul>
     * 
     * For example, literal string value becomes
     * <pre>
     *   no\"effect       no\"effect   (no surrounding quotes returns the input)
     *   "one"            one          (unquoted)
     *   "one\"two\""     one"two"     (quote character escaped)
     *   "one\\"two"      one\"two     (technically an unescaped backslash is
     *                                  not removed, has same result as if it
     *                                  was escaped)
     *   "one\\\"two"     one\"two     (like this)
     *   "one\\\\"two"    one\\"two    (escaped quote and escaped backslash)
     * </pre>
     * 
     * @param str to unquote
     * @return an unquoted string
     * 
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.2.6">RFC 7230 §3.2.6</a>
     */
    public static String unquote(String str) {
        if (str.length() <= 2 || !(str.startsWith("\"") && str.endsWith("\""))) {
            return str;
        }
        return str.substring(1, str.length() -1)
                // If there's ever a problem with this method (added in Java 15),
                // try the old implementation instead:
                //     .replace("\\\"", "\"")
                //     .replace("\\\\", "\\")
                .translateEscapes()
                .strip();
    }
    
    /**
     * Similar to {@link String#contains(CharSequence)}, except without regards
     * to casing.
     * 
     * @param str left operand
     * @param substr right operand
     * 
     * @return {@code true} if {@code str} contains {@code substr},
     *         otherwise {@code false}
     * 
     * @throws NullPointerException if any arg is {@code null}
     */
    public static boolean containsIgnoreCase(String str, String substr) {
        // Sourced from:
        // https://github.com/apache/commons-lang/blob/c1a0c26c305919c698196b857899e7e4725b0c45/src/main/java/org/apache/commons/lang3/StringUtils.java#L1238
        final int len = substr.length(),
                  max = str.length() - len;
        
        for (int i = 0; i <= max; i++) {
            if (str.regionMatches(true, i, substr, 0, len)) {
                return true;
            }
        }
        return false;
    }
}
