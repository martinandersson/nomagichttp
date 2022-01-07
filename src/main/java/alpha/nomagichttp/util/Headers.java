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
     * Create headers out of a name-value pair array.<p>
     * 
     * All strings indexed with an even number is the header name. All strings
     * indexed with an odd number is the header value.<p>
     * 
     * Header values may be repeated, see {@link HttpHeaders}.
     * 
     * @param nameValuePairs header entries
     * @return HttpHeaders
     * 
     * @throws NullPointerException
     *             if {@code nameValuePairs} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code nameValuePairs.length} is not even
     */
    public static HttpHeaders of(String... nameValuePairs) {
        if (nameValuePairs.length == 0) {
            return EMPTY;
        }
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Please provide an even number of pairs.");
        }
        
        var map = new HashMap<String, List<String>>();
        for (int i = 0; i < nameValuePairs.length - 1; i += 2) {
            String k = nameValuePairs[i],
                   v = nameValuePairs[i + 1];
            map.computeIfAbsent(k, k0 -> new ArrayList<>(1)).add(v);
        }
        
        return of(map);
    }
    
    /**
     * Equivalent to {@link HttpHeaders#of(Map, BiPredicate)} where the
     * filter used accepts all entries.
     * 
     * @param map all header mappings
     * @return HttpHeaders
     * @throws NullPointerException if {@code map} is {@code null}
     */
    public static HttpHeaders of(Map<String,List<String>> map) {
        if (map.isEmpty()) {
            return EMPTY;
        }
        // Seriously, why does the filter even exist? It's a code smell miles away.
        return HttpHeaders.of(map, (k, v) -> true);
    }
}