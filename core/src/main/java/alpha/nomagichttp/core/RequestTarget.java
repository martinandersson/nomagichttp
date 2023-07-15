package alpha.nomagichttp.core;

import alpha.nomagichttp.message.Request;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static alpha.nomagichttp.core.Segments.ASTERISK_CH;
import static alpha.nomagichttp.core.Segments.COLON_CH;
import static java.util.Collections.unmodifiableMap;

/**
 * Default implementation of {@code Request.Target}.<p>
 * 
 * This class gets most if its data from {@link SkeletonRequestTarget}. What
 * this class may add is path parameters, which are mapped from the resource's
 * segments/pattern.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestTarget implements Request.Target
{
    static RequestTarget requestTargetWithParams(
            SkeletonRequestTarget rt, Iterable<String> resourceSegments) {
        return new RequestTarget(rt, resourceSegments);
    }
    
    static RequestTarget requestTargetWithoutParams(
            SkeletonRequestTarget rt) {
        return new RequestTarget(rt, null);
    }
    
    private final SkeletonRequestTarget rt;
    private final Iterable<String> resourceSegments;
    
    private RequestTarget(
            SkeletonRequestTarget rt, Iterable<String> resourceSegments) {
        this.rt = rt;
        this.resourceSegments = resourceSegments;
    }
    
    @Override
    public String raw() {
        return rt.raw();
    }
    
    @Override
    public List<String> segments() {
        return rt.segments();
    }
    
    @Override
    public List<String> segmentsRaw() {
        return rt.segmentsRaw();
    }
    
    @Override
    public String pathParam(String name) {
        return pathParamMap().get(name);
    }
    
    @Override
    public Map<String, String> pathParamMap() {
        return params().decoded;
    }
    
    @Override
    public String pathParamRaw(String name) {
        return pathParamRawMap().get(name);
    }
    
    @Override
    public Map<String, String> pathParamRawMap() {
        return params().raw;
    }
    
    private PathParams params;
    
    private PathParams params() {
        if (resourceSegments == null) {
            throw new UnsupportedOperationException(
                    "Path parameters are not available");
        }
        var p = params;
        return p != null ? p : (params = new PathParams());
    }
    
    private final class PathParams {
        final Map<String, String> raw, decoded;
        PathParams() {
            // We need to map "request/path/segments" to "resource/:path/*parameters"
            Iterator<String>    decIt  = segments().iterator(),
                                segIt  = resourceSegments.iterator();
            Map<String, String> rawMap = Map.of(),
                                decMap = Map.of();
            
            String catchAllKey = null;
            
            for (String r : segmentsRaw()) {
                String d = decIt.next();
                
                if (catchAllKey == null) {
                    // Catch-all not activated, consume next resource segment
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
            
            // We're done with the request path, but resource may still have a catch-all segment in there
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
            this.raw = unmodifiableMap(rawMap);
            this.decoded = unmodifiableMap(decMap);
        }
    }
    
    private static <K, V> Map<K, V> mk(Map<K, V> map) {
        return map.isEmpty() ? new HashMap<>() : map;
    }
    
    @Override
    public Optional<String> queryFirst(String key) {
        return queryStream(key).findFirst();
    }
    
    @Override
    public Optional<String> queryFirstRaw(String key) {
        return queryStreamRaw(key).findFirst();
    }
    
    @Override
    public Stream<String> queryStream(String key) {
        return queryList(key).stream();
    }
    
    @Override
    public Stream<String> queryStreamRaw(String key) {
        return queryListRaw(key).stream();
    }
    
    @Override
    public List<String> queryList(String key) {
        return queryMap().getOrDefault(key, List.of());
    }
    
    @Override
    public List<String> queryListRaw(String key) {
        return queryMapRaw().getOrDefault(key, List.of());
    }
    
    @Override
    public Map<String, List<String>> queryMap() {
        return rt.queryMap();
    }
    
    @Override
    public Map<String, List<String>> queryMapRaw() {
        return rt.queryMapRaw();
    }
    
    @Override
    public String fragment() {
        return rt.fragment();
    }
}