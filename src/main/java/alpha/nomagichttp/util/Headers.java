package alpha.nomagichttp.util;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import static java.util.Collections.emptyMap;

/**
 * Utility methods for constructing {@link HttpHeaders}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public final class Headers
{
    private static final HttpHeaders EMPTY
            = HttpHeaders.of(emptyMap(), (ign,ored) -> false);
    
    private Headers() {
        // Empty
    }
    
    /**
     * Create headers out of a key-value pair array.<p>
     * 
     * All strings indexed with an even number is the header key. All strings
     * indexed with an odd number is the header value.<p>
     * 
     * Header values may be repeated, see {@link HttpHeaders}.
     * 
     * @param keyValuePairs header entries
     * @return HttpHeaders
     * 
     * @throws NullPointerException
     *             if {@code keyValuePairs} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code keyValuePairs.length} is not even
     */
    public static HttpHeaders of(String... keyValuePairs) {
        if (keyValuePairs.length == 0) {
            return EMPTY;
        }
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Please provide an even number of pairs.");
        }
        
        var map = new HashMap<String, List<String>>();
        for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
            String k = keyValuePairs[i],
                   v = keyValuePairs[i + 1];
            map.computeIfAbsent(k, k0 -> new ArrayList<>(1)).add(v);
        }
        
        return of(map);
    }
    
    /**
     * Equivalent to {@link HttpHeaders#of(Map, BiPredicate)} where the
     * filter used accepts all entries.<p>
     * 
     * Seriously, why does the filter even exist lol? It's a code smell miles
     * away.
     * 
     * @param map all header mappings
     * @return HttpHeaders
     * @throws NullPointerException if {@code keyValuePairs} is {@code null}
     */
    public static HttpHeaders of(Map<String,List<String>> map) {
        if (map.isEmpty()) {
            return EMPTY;
        }
        return HttpHeaders.of(map, (k, v) -> true);
    }
}