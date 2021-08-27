package alpha.nomagichttp.internal;

import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.PercentDecoder;

import java.util.ArrayList;
import java.util.List;
import java.util.RandomAccess;

import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.toCollection;

/**
 * A thin version of a request target.<p>
 * 
 * This version is constructed at an early stage of the HTTP exchange and is
 * primarily useful for iterating segments of the request path, in turn used to
 * lookup resources from registries. The real {@link RequestTarget} is built
 * after the lookup and will need access to the resource-declared segments for
 * processing resource-specific path parameters.
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
    
    private final String rt;
    private final List<String> segmentsNotPercentDecoded;
    private final String query;
    private final String fragment;
    
    private <L extends List<String> & RandomAccess> SkeletonRequestTarget(
            String rt, L segmentsNotPercentDecoded, String query, String fragment)
    {
        this.rt = rt;
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
        return rt;
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
        return unmodifiableList(segmentsRaw().stream()
                .map(PercentDecoder::decode)
                .collect(toCollection(() ->
                        new ArrayList<>(segmentsRaw().size()))));
    }
    
    /**
     * Equivalent to {@link RequestTarget#segmentsRaw()}.
     * 
     * @return see JavaDoc
     */
    List<String> segmentsRaw() {
        return segmentsNotPercentDecoded;
    }
    
    /**
     * Returns the raw query string.<p>
     * 
     * The returned string does not start with "?".
     * 
     * @return the raw query string
     */
    String query() {
        return query;
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