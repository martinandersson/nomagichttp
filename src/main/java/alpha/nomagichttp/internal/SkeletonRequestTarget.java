package alpha.nomagichttp.internal;

import alpha.nomagichttp.ReceiverOfUniqueRequestObject;
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
import static java.util.stream.Collectors.toMap;

/**
 * An almost complete version of {@link RequestTarget}. The one thing missing is
 * support for path parameters as these are unique per {@link
 * ReceiverOfUniqueRequestObject}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class SkeletonRequestTarget
{
    /**
     * Parse the given input.<p>
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
     * See sections "3.3 Path", "3.4 Query" and "3.5 Fragment" respectively in
     * <a href="https://tools.ietf.org/html/rfc3986#section-3.3">RFC 3986</a>.
     * 
     * @param rt raw request target as read from the request-line
     * @return a complex type of the input
     * @throws NullPointerException if {@code rt} is {@code null}
     */
    static SkeletonRequestTarget parse(String rt) {
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
        
        final String query;
        if (q == -1) {
            query = "";
        } else if (f != -1) {
            query = parse.substring(q + 1, f);
        } else {
            query = parse.substring(q + 1);
        }
        
        final String fragment;
        if (f == -1) {
            fragment = "";
        } else {
            fragment = parse.substring(f + 1);
        }
        
        return new SkeletonRequestTarget(rt, keep, query, fragment);
    }
    
    private final String raw;
    private final List<String> segmentsNotPercentDecoded;
    private final String query;
    private final String fragment;
    
    private <L extends List<String> & RandomAccess> SkeletonRequestTarget(
            String raw, L segmentsNotPercentDecoded, String query, String fragment)
    {
        this.raw = raw;
        this.segmentsNotPercentDecoded = unmodifiableList(segmentsNotPercentDecoded);
        this.query = query;
        assert !query.startsWith("?");
        this.fragment = fragment;
        assert !fragment.startsWith("#");
    }
    
    /**
     * Equivalent to {@link RequestTarget#raw()}.
     * 
     * @return see JavaDoc
     */
    String raw() {
        return raw;
    }
    
    private List<String> segmentsPercentDecoded;
    
    /**
     * Equivalent to {@link RequestTarget#segments()}.
     * 
     * @return see JavaDoc
     */
    List<String> segments() {
        var s = segmentsPercentDecoded;
        return s != null ? s : (segmentsPercentDecoded = mkSegments());
    }
    
    private List<String> mkSegments() {
        return segmentsRaw().stream().map(PercentDecoder::decode).toList();
    }
    
    /**
     * Equivalent to {@link RequestTarget#segmentsRaw()}.
     * 
     * @return see JavaDoc
     */
    List<String> segmentsRaw() {
        return segmentsNotPercentDecoded;
    }
    
    private Map<String, List<String>> queryMapPercentDecoded;
    
    /**
     * Equivalent to {@link RequestTarget#queryMap()}.
     * 
     * @return see JavaDoc
     */
    Map<String, List<String>> queryMap() {
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
    
    /**
     * Equivalent to {@link RequestTarget#queryMapRaw()}.
     * 
     * @return see JavaDoc
     */
    Map<String, List<String>> queryMapRaw() {
        Map<String, List<String>> m = queryMapNotPercentDecoded;
        return m != null ? m : (queryMapNotPercentDecoded = parseQuery());
    }
    
    private Map<String, List<String>> parseQuery() {
        if (query.isEmpty()) {
            return Map.of();
        }
        
        final var m = new LinkedHashMap<String, List<String>>();
        String[] pairs = query.split("&");
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
    
    /**
     * Equivalent to {@link RequestTarget#fragment()}.
     * 
     * @return see JavaDoc
     */
    String fragment() {
        return fragment;
    }
}