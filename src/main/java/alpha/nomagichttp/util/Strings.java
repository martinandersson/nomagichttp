package alpha.nomagichttp.util;

import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;

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
     * Works just as {@code String.split}, except this method accepts an exclude
     * boundary within which, the delimiter will have no effect and the substring
     * will be taken at face value.<p>
     * 
     * For example, good to use when dealing with strings that have quoted parts
     * in them and the split shouldn't occur within those quoted parts.
     * 
     * @param string input string to split
     * @param delimiter char to split by...
     * @param excludeBoundary ...except if found within this boundary
     * 
     * @return the substrings
     */
    // TODO: Make sure we can handle an escaped quote within a quoted string! "my \"quote\" ..."
    // See https://tools.ietf.org/html/rfc7230#section-3.2.6
    // Ex. https://tools.ietf.org/html/rfc7235#section-4.1
    static String[] split(String string, char delimiter, char excludeBoundary) {
        if (delimiter == excludeBoundary) {
            throw new IllegalArgumentException(
                    "Delimiter char can not be same as exclude char.");
        }
        
        PrimitiveIterator.OfInt chars = string.chars().iterator();
        
        StringBuilder curr = null;
        boolean ignoring = false;
        List<String> bucket = null;
        
        while (chars.hasNext()) {
            final char c = (char) chars.nextInt();
            
            if (c == delimiter) {
                if (ignoring) {
                    curr.append(c);
                    continue; }
                
                if (curr == null) {
                    continue; }
                
                if (bucket == null) {
                    bucket = new ArrayList<>(); }
                
                bucket.add(curr.toString());
                curr = null;
            } else {
                if (curr == null) {
                    curr = new StringBuilder(); }
                
                curr.append(c);
                
                if (c == excludeBoundary) {
                    ignoring = !ignoring; }
            }
        }
        
        if (curr != null && !ignoring) {
            if (bucket == null) {
                bucket = new ArrayList<>(); }
            
            bucket.add(curr.toString());
        }
        
        return bucket == null ?
                new String[]{} :
                bucket.toArray(String[]::new);
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
