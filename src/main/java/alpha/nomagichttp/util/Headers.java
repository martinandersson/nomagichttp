package alpha.nomagichttp.util;

import alpha.nomagichttp.message.BetterHeaders;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;

import static java.lang.String.CASE_INSENSITIVE_ORDER;

/**
 * Utility methods for constructing {@link HttpHeaders}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Make this into a test util
//       Then add toLinkedHashMap(BetterHeaders) (and remove other code that makes a copy)
public final class Headers
{
    private Headers() {
        // Empty
    }
    
    /**
     * Create headers out of a name-value pair array.<p>
     * 
     * All strings indexed with an even number is the header name. All strings
     * indexed with an odd number is the header value.
     * 
     * @param nameValuePairs header entries
     * @return headers
     * 
     * @throws NullPointerException
     *             if {@code nameValuePairs} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code nameValuePairs.length} is not even
     */
    public static LinkedHashMap<String, List<String>> of(String... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Please provide an even number of pairs.");
        }
        var map = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < nameValuePairs.length - 1; i += 2) {
            String k = nameValuePairs[i],
                   v = nameValuePairs[i + 1];
            map.computeIfAbsent(k, k0 -> new ArrayList<>(1)).add(v);
        }
        return map;
    }
    
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