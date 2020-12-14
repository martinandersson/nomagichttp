package alpha.nomagichttp.internal;

import alpha.nomagichttp.route.Route;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;

/**
 * Consists of path segments and query key/value pairs; as split and parsed
 * from a raw request-target string.<p>
 * 
 * The implementation is thread-safe and non-blocking.<p>
 * 
 * See sections "3.3 Path",
 * "3.4 Query" and "3.5 Fragment" respectively in
 * <a href="https://tools.ietf.org/html/rfc3986#section-3.3">RFC 3986</a>.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class RequestTarget
{
    /**
     * Create a {@code RequestTarget} from the given input.<p>
     * 
     * The request path will be worked on according to what has been specified
     * in {@link Route}. Specifically:
     * 
     * <ul>
     *   <li>Clustered forward slashes are reduced to just one.</li>
     *   <li>All trailing forward slashes are truncated.</li>
     *   <li>The empty path will be replaced with "/".</li>
     *   <li>Dot-segments (".", "..") are normalized.</li>
     * </ul>
     * 
     * Path segments will be percent-decoded eagerly (needs to be compared with
     * route segments). Query key- and values will only be percent-decoded when
     * accessed by application code.
     * 
     * @param rt raw request target as read from the request-line
     * 
     * @return a complex type representing the input
     * 
     * @throws NullPointerException
     *             if {@code rt} is {@code null}
     * 
     * @throws IllegalArgumentException
     *             if the decoder encounters illegal characters
     */
    static RequestTarget parse(final String rt) {
        // At the very least, we're parsing a "/"
        String parse = rt.isEmpty() ? "/" : rt;
        assert !parse.isBlank() : "RequestHeadProcessor should not give us a blank string.";
        
        final int skip = parse.startsWith("/") ? 1 : 0;
        
        // Find query- and fragment indices
        int q = parse.indexOf('?', skip),
            f = parse.indexOf('#', q == -1 ? skip : q + 1);
        
        // Extract path component..
        final String path;
        if (q == -1 && f == -1) {
            path = parse.substring(skip);
        } else if (q != -1) {
            path = parse.substring(skip, q);
        } else {
            assert f != -1;
            path = parse.substring(skip, f);
        }
        
        // ..into tokens that we normalize
        String[] segments = path.split("/");
        ArrayList<String> keep = new ArrayList<>();
        
        for (String t : segments) {
            // Drop "" and "."
            if (t.isEmpty() || t.equals(".")) {
                continue;
            }
            // ".." removes the previous one if and only if previous is not ".."
            if (t.equals("..")  &&
                !keep.isEmpty() &&
                !keep.get(keep.size() - 1).equals(".."))
            {
                keep.remove(keep.size() - 1);
            // Keep legit values and ineffective ".."
            } else {
                keep.add(t);
            }
        }
        
        // Extract query string
        final String query;
        if (q == -1) {
            query = "";
        } else if (f != -1) {
            query = parse.substring(q + 1, f);
        } else {
            query = parse.substring(q + 1);
        }
        
        return new RequestTarget(rt, keep, query);
    }
    
    private final String rtRaw;
    private final String query;
    private final List<String>
            segmentsNotPercentDecoded, segmentsPercentDecoded;
    private Map<String, List<String>>
            queryMapNotPercentDecoded, queryMapPercentDecoded;
    
    <L extends List<String> & RandomAccess> RequestTarget(
            String rtRaw, L segmentsNotPercentDecoded, String query)
    {
        this.rtRaw = rtRaw;
        this.query = query;
        assert !query.startsWith("?");
        this.segmentsNotPercentDecoded = unmodifiableList(segmentsNotPercentDecoded);
        this.segmentsPercentDecoded = percentDecode(segmentsNotPercentDecoded);
    }
    
    /**
     * Returns the raw non-normalized and not percent-decoded request-target.
     * 
     * @return the raw non-normalized and not percent-decoded request-target
     */
    String pathRaw() {
        return rtRaw;
    }
    
    /**
     * Returns normalized but possibly escaped segments.<p>
     * 
     * The returned list implements RandomAccess and is unmodifiable.
     * 
     * @return normalized but possibly escaped segments
     */
    List<String> segmentsNotPercentDecoded() {
        return segmentsNotPercentDecoded;
    }
    
    /**
     * Returns normalized and percent-decoded segments.<p>
     * 
     * The returned list implements RandomAccess and is unmodifiable.
     * 
     * @return normalized and percent-decoded segments
     */
    List<String> segmentsPercentDecoded() {
        return segmentsPercentDecoded;
    }
    
    /**
     * Returns possibly escaped query entries.<p>
     * 
     * The returned map and list values are unmodifiable. In addition, the list
     * values implement RandomAccess.
     * 
     * @return normalized but possibly escaped query entries
     */
    Map<String, List<String>> queryMapNotPercentDecoded() {
        Map<String, List<String>> m = queryMapNotPercentDecoded;
        return m != null ? m : (queryMapNotPercentDecoded = parseQuery());
    }
    
    private Map<String, List<String>> parseQuery() {
        if (query.isEmpty()) {
            return Map.of();
        }
        
        Map<String, List<String>> m = new LinkedHashMap<>();
        
        String[] pairs = query.split("&");
        
        for (String p : pairs) {
            int i = p.indexOf('=');
            // note: value may be the empty string!
            String k = p.substring(0, i == -1 ? p.length(): i),
                   v = i == -1 ? "" : p.substring(i + 1);
            
            List<String> l = m.computeIfAbsent(k, ignored -> new ArrayList<>(1));
            l.add(v);
        }
        
        m.entrySet().forEach(e ->
            e.setValue(unmodifiableList(e.getValue())));
        
        return unmodifiableMap(m);
    }
    
    /**
     * Returns normalized and percent-decoded query entries.<p>
     * 
     * The returned map and list values are unmodifiable. In addition, the list
     * values implement RandomAccess.
     * 
     * @return normalized and percent-decoded query entries
     */
    Map<String, List<String>> queryMapPercentDecoded() {
        Map<String, List<String>> m = queryMapPercentDecoded;
        return m != null ? m : (queryMapPercentDecoded = decodeMap());
    }
    
    private Map<String, List<String>> decodeMap() {
        Map<String, List<String>> m = queryMapNotPercentDecoded().entrySet().stream().collect(toMap(
                e -> percentDecode(e.getKey()),
                e -> percentDecode(e.getValue()),
                (ign,ored) -> { throw new AssertionError("Insufficient JDK API"); },
                LinkedHashMap::new));
        
        return unmodifiableMap(m);
    }
    
    private static String percentDecode(String str) {
        final int p = str.indexOf('+');
        if (p == -1) {
            // No plus characters? JDK-decode the entire string
            return URLDecoder.decode(str, UTF_8);
        } else {
            // Else decode chunks in-between
            return percentDecode(str.substring(0, p)) + "+" + percentDecode(str.substring(p + 1));
        }
    }
    
    private static List<String> percentDecode(Collection<String> strings) {
        // Collectors.toUnmodifiableList() does not document RandomAccess
        List<String> l = strings.stream()
                .map(RequestTarget::percentDecode)
                .collect(toCollection(ArrayList::new));
        
        return unmodifiableList(l);
    }
}