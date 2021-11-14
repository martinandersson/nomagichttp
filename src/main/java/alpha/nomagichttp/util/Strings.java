package alpha.nomagichttp.util;

import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;

import static java.lang.Character.MIN_VALUE;

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
     * Split a string into an array of tokens.<p>
     * 
     * Works just as {@code String.split}, except this method respects exclusion
     * zones within which, the delimiter will have no effect. Also, this method
     * never returns empty tokens.<p>
     * 
     * For example, good to use when substrings may be quoted and no split
     * should occur within the quoted parts.<p>
     * 
     * For example
     * <pre>
     *   split("one.two", '.', '"') -> ["one", "two]
     *   split("one.\"keep.this\"", '.', '"') -> ["one", ""keep.this""]
     *   split("...", '.', '"') -> []
     * </pre>
     * 
     * Note how the quoted part is kept intact. The call site would likely want
     * to de-quote the quoted part.<p>
     * 
     * A preceding backslash character is interpreted as escaping the delimiter,
     * but only within exclusion zones. Just as with the delimiter character,
     * also the escaping backslash is kept intact.
     * <pre>
     *   split("one.\"t\\\"w.o\"", '.', '"') -> ["one", ""t\"w.o""]
     * </pre>
     * 
     * Outside an exclusion zone, the backslash character is just like any other
     * character.
     * <pre>
     *   split("one\\.two", '.', '"') -> ["one\", "two"]
     * </pre>
     * 
     * @param string input string to split
     * @param delimiter char to split by...
     * @param excludeBoundary ...except if found within this boundary
     * 
     * @return the substrings
     * 
     * @throws IllegalArgumentException
     *             if {@code delimiter} and {@code excludeBoundary}
     *             are the same char
     */
    public static String[] split(String string, char delimiter, char excludeBoundary) {
        if (delimiter == excludeBoundary) {
            throw new IllegalArgumentException(
                    "Delimiter char can not be same as exclude char.");
        }
        
        PrimitiveIterator.OfInt chars = string.chars().iterator();
        
        StringBuilder tkn = null;
        List<String> sink = null;
        boolean excluding = false;
        
        char prev = MIN_VALUE;
        while (chars.hasNext()) {
            boolean split;
            final char c = (char) chars.nextInt();
            
            if (prev == '\\' && excluding) {
                // Whatever c is, keep building current token
                split = false;
            } else if (c == delimiter) {
                // We split only if we're not excluding
                split = !excluding;
            } else if (c == excludeBoundary) {
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
                // Push what we had and begin new token
                if (tkn != null) {
                    if (sink == null) {
                        sink = new ArrayList<>();
                    }
                    sink.add(tkn.toString());
                    tkn = null;
                }
            } else {
                // Add c to current token
                if (tkn == null) {
                    tkn = new StringBuilder();
                }
                tkn.append(c);
            }
        }
        
        if (tkn != null) {
            if (sink == null) {
                sink = new ArrayList<>();
            }
            sink.add(tkn.toString());
        }
        
        return sink == null ? EMPTY : sink.toArray(String[]::new);
    }
    
    private static final String[] EMPTY = {};
    
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
