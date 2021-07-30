package alpha.nomagichttp.internal;

import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.PercentDecoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

import static alpha.nomagichttp.util.PercentDecoder.decode;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * Consists of path segments and query key/value pairs; as split and parsed
 * from a raw request-target string.<p>
 * 
 * The query maps returned by this class are unmodifiable and retain the same
 * iteration order as the query keys were declared in the query string.<p>
 * 
 * All lists returned by this class are unmodifiable and implements {@link
 * RandomAccess}.<p>
 * 
 * The root ("/") is not represented in the returned lists of segments. If the
 * parsed request path was empty or effectively a single "/", then the returned
 * lists will also be empty. This has been officially specified by {@link
 * Route#segments()} and is an expected de-facto norm throughout the library
 * code.<p>
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
     * Parsing- and percent-decoding the query string takes place lazily upon
     * first access, and so, is a cost not paid by clients uninterested of it.
     * 
     * @param rt raw request target as read from the request-line
     * @return a complex type representing the input
     * @throws NullPointerException if {@code rt} is {@code null}
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
        
        // ..into tokens that we normalize, using .split(). E.g.
        //     "a/b"  => ["a", "b"]
        //     "a/"   => ["a"]         (here '/' is removed)
        //     "/a"   => ["", "a"]     (not symmetric lol, here a magical empty token appears)
        //     "a"    => ["a"]
        //     ""     => [""]          (yikes! empty input produces array.length == 1)
        //     "/"    => []            (...by themselves no magic tokens lol)
        //     "//"   => []
        //     "///"  => []
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
    private final List<String> segmentsNotPercentDecoded,
                               segmentsPercentDecoded;
    private Map<String, List<String>>
            queryMapNotPercentDecoded, queryMapPercentDecoded;
    
    <L extends List<String> & RandomAccess> RequestTarget(
            String rtRaw, L segmentsNotPercentDecoded, String query)
    {
        this.rtRaw = rtRaw;
        this.query = query;
        assert !query.startsWith("?");
        this.segmentsNotPercentDecoded = unmodifiableList(segmentsNotPercentDecoded);
        this.segmentsPercentDecoded    = unmodifiableList(segmentsNotPercentDecoded.stream()
                .map(PercentDecoder::decode).collect(toList()));
        
        this.queryMapNotPercentDecoded = null;
        this.queryMapPercentDecoded = null;
    }
    
    /**
     * Returns the raw non-normalized and not percent-decoded request-target.<p>
     * 
     * The returned value is in fact the same string passed to the parse method.
     * I.e, what was received on the wire in the request head.
     * 
     * @return the raw non-normalized and not percent-decoded request-target
     */
    String pathRaw() {
        return rtRaw;
    }
    
    /**
     * Returns normalized but possibly escaped segments.
     * 
     * @return normalized but possibly escaped segments
     */
    List<String> segmentsNotPercentDecoded() { // TODO: rename to segmentsRaw
        return segmentsNotPercentDecoded;
    }
    
    /**
     * Returns normalized and escaped segments.
     * 
     * @return normalized and escaped segments
     */
    List<String> segmentsPercentDecoded() {
        return segmentsPercentDecoded;
    }
    
    /**
     * Returns possibly escaped query entries.
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
     * Returns normalized and percent-decoded query entries.
     * 
     * @return normalized and percent-decoded query entries
     */
    Map<String, List<String>> queryMapPercentDecoded() {
        Map<String, List<String>> m = queryMapPercentDecoded;
        return m != null ? m : (queryMapPercentDecoded = decodeMap());
    }
    
    private Map<String, List<String>> decodeMap() {
        Map<String, List<String>> m = queryMapNotPercentDecoded().entrySet().stream().collect(toMap(
                e -> decode(e.getKey()),
                e -> decode(e.getValue()),
                (ign,ored) -> { throw new AssertionError("Insufficient JDK API"); },
                LinkedHashMap::new));
        
        return unmodifiableMap(m);
    }
}