package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.Request;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static alpha.nomagichttp.internal.Segments.ASTERISK_CH;
import static alpha.nomagichttp.internal.Segments.COLON_CH;
import static alpha.nomagichttp.util.PercentDecoder.decode;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.toMap;

/**
 * Default implementation of {@link Request.Target}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestTarget implements Request.Target
{
    private final SkeletonRequestTarget rt;
    private final Iterable<String> resourceSegments;
    
    RequestTarget(SkeletonRequestTarget rt, Iterable<String> resourceSegments) {
        assert rt != null;
        assert resourceSegments != null;
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
    
    private Map<String, List<String>> queryMapPercentDecoded;
    
    @Override
    public Map<String, List<String>> queryMap() {
        Map<String, List<String>> m = queryMapPercentDecoded;
        return m != null ? m : (queryMapPercentDecoded = decodeMap());
    }
    
    private Map<String, List<String>> decodeMap() {
        var decoded = queryMapRaw().entrySet().stream().collect(toMap(
                e -> decode(e.getKey()),
                e -> decode(e.getValue()),
                (ign,ored) -> { throw new AssertionError("Insufficient JDK API"); },
                LinkedHashMap::new));
        return unmodifiableMap(decoded);
    }
    
    private Map<String, List<String>> queryMapNotPercentDecoded;
    
    @Override
    public Map<String, List<String>> queryMapRaw() {
        Map<String, List<String>> m = queryMapNotPercentDecoded;
        return m != null ? m : (queryMapNotPercentDecoded = parseQuery());
    }
    
    private Map<String, List<String>> parseQuery() {
        final var q = rt.query();
        if (q.isEmpty()) {
            return Map.of();
        }
        
        final var m = new LinkedHashMap<String, List<String>>();
        String[] pairs = q.split("&");
        for (String p : pairs) {
            int i = p.indexOf('=');
            // note: value may be the empty string!
            String k = p.substring(0, i == -1 ? p.length(): i),
                   v = i == -1 ? "" : p.substring(i + 1);
            m.computeIfAbsent(k, key -> new ArrayList<>(1)).add(v);
        }
        m.entrySet().forEach(e ->
            e.setValue(unmodifiableList(e.getValue())));
        
        return unmodifiableMap(m);
    }
    
    @Override
    public String fragment() {
        return rt.fragment();
    }
}