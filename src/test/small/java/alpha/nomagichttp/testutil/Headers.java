package alpha.nomagichttp.testutil;

import alpha.nomagichttp.message.BetterHeaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

import static java.lang.String.CASE_INSENSITIVE_ORDER;

/**
 * Utility methods for constructing header {@code Map}s.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Headers
{
    private Headers() {
        // Empty
    }
    
    /**
     * Constructs a multivalued {@code LinkedHashMap}.<p>
     * 
     * The returned map will obviously retain the provided order of headers, but
     * does not provide a case-insensitive {@code equals} method for the header
     * names.
     * 
     * @param nameValuePairs header entries
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code nameValuePairs} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code nameValuePairs.length} is not even
     * 
     * @see #treeMap(BetterHeaders)
     */
    public static LinkedHashMap<String, List<String>> linkedHashMap(
            String... nameValuePairs)
    {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Please provide an even number of pairs.");
        }
        var map = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < nameValuePairs.length - 1; i += 2) {
            String k = nameValuePairs[i],
                   v = nameValuePairs[i + 1];
            map.computeIfAbsent(k, k0 -> new ArrayList<>(1)).add(v);
        }
        return map;
    }
    
    /**
     * Copies the given headers into a multivalued, case-insensitive
     * {@code TreeMap}.<p>
     * 
     * The returned map will be equal to another {@code TreeMap}, as long as the
     * other map contains the same set of header names (case-insensitive) and
     * values (case-sensitive).<p>
     * 
     * The iteration order of the returned map is defined by
     * {@link String#compareToIgnoreCase(String)}.
     * 
     * @param headers to copy
     * 
     * @return see JavaDoc
     * 
     * @throws NullPointerException
     *             if {@code headers} is {@code null}
     * 
     * @see #linkedHashMap(String...) 
     */
    public static TreeMap<String, List<String>> treeMap(BetterHeaders headers) {
        var map = new TreeMap<String, List<String>>(CASE_INSENSITIVE_ORDER);
        headers.forEach(map::put);
        return map;
    }
    
    public static TreeMap<String, List<String>> treeMap(String... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Please provide an even number of pairs.");
        }
        var map = new TreeMap<String, List<String>>(CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < nameValuePairs.length - 1; i += 2) {
            String k = nameValuePairs[i],
                   v = nameValuePairs[i + 1];
            map.computeIfAbsent(k, k0 -> new ArrayList<>(1)).add(v);
        }
        return map;
    }
}