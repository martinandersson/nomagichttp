package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Request;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static alpha.nomagichttp.internal.Segments.ASTERISK_CH;
import static alpha.nomagichttp.internal.Segments.COLON_CH;

/**
 * A match of a resource from a registry.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * @param <T> resource type
 */
final class ResourceMatch<T>
{
    private final T resource;
    private final Map<String, String> paramsRaw, paramsDec;
    
    private ResourceMatch(T resource, Map<String, String> paramsRaw, Map<String, String> paramsDec) {
        this.resource  = resource;
        this.paramsRaw = paramsRaw;
        this.paramsDec = paramsDec;
    }
    
    /**
     * Returns the matched resource.
     * 
     * @return the matched resource (never {@code null})
     */
    T get() {
        return resource;
    }
    
    /**
     * Equivalent to {@link Request.Parameters#path(String)}.
     * 
     * @param name of path parameter (case-sensitive)
     * @return the path parameter value (percent-decoded)
     */
    String pathParam(String name) {
        return paramsDec.get(name);
    }
    
    /**
     * Equivalent to {@link Request.Parameters#pathRaw(String)}.
     * 
     * @param name of path parameter (case-sensitive)
     * @return the raw path parameter value (not decoded/unescaped)
     */
    String pathParamRaw(String name) {
        return paramsRaw.get(name);
    }
    
    /**
     * FOR TESTS ONLY: Returns the internal map holding raw path parameter values.
     * 
     * @return internal map
     */
    Map<String, String> mapRaw() {
        return paramsRaw;
    }
    
    /**
     * FOR TESTS ONLY:  Returns the internal map holding decoded path parameter
     * values.
     * 
     * @return internal map
     */
    Map<String, String> mapDec() {
        return paramsDec;
    }
    
    static <T> ResourceMatch<T> of(RequestTarget rt, T resource, Iterable<String> resourceSegments) {
        // We need to map "request/path/segments" to "resource/:path/*parameters"
        Iterator<String>    decIt  = rt.segmentsPercentDecoded().iterator(),
                            segIt  = resourceSegments.iterator();
        Map<String, String> rawMap = Map.of(),
                            decMap = Map.of();
        
        String catchAllKey = null;
        
        for (String r : rt.segmentsNotPercentDecoded()) {
            String d = decIt.next();
            
            if (catchAllKey == null) {
                // Catch-all not activated, consume next route segment
                String s = segIt.next();
                
                switch (s.charAt(0)) {
                    case COLON_CH:
                        // Single path param goes to map
                        String k = s.substring(1),
                                o = (rawMap = mk(rawMap)).put(k, r);
                        assert o == null;
                        o = (decMap = mk(decMap)).put(k, d);
                        assert o == null;
                        break;
                    case ASTERISK_CH:
                        // Toggle catch-all phase with this segment as seed
                        catchAllKey = s.substring(1);
                        (rawMap = mk(rawMap)).put(catchAllKey, '/' + r);
                        (decMap = mk(decMap)).put(catchAllKey, '/' + d);
                        break;
                    default:
                        // Static segments we're not interested in
                        break;
                }
            } else {
                // Consume all remaining request segments as catch-all
                rawMap.merge(catchAllKey, '/' + r, String::concat);
                decMap.merge(catchAllKey, '/' + d, String::concat);
            }
        }
        
        // We're done with the request path, but route may still have a catch-all segment in there
        if (segIt.hasNext()) {
            String s = segIt.next();
            assert s.startsWith("*");
            assert !segIt.hasNext();
            assert catchAllKey == null;
            catchAllKey = s.substring(1);
        }
        
        // We could have toggled to catch-all, but no path segment was consumed for it, and
        if (catchAllKey != null && !rawMap.containsKey(catchAllKey)) {
            // route JavaDoc promises to default with a '/'
            (rawMap = mk(rawMap)).put(catchAllKey, "/");
            (decMap = mk(decMap)).put(catchAllKey, "/");
        }
        
        assert !decIt.hasNext();
        return new ResourceMatch<>(resource, rawMap, decMap);
    }
    
    private static <K, V> Map<K, V> mk(Map<K, V> map) {
        return map.isEmpty() ? new HashMap<>() : map;
    }
}