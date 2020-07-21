package alpha.nomagichttp.route;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Behaves as a mutable {@code Segment} and performs input argument
 * validation.<p>
 * 
 * As long as no parameters have been added, the segment can be extended using
 * {@link #concat(String)}.<p>
 * 
 * This class is not thread-safe.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Segment
 */
final class SegmentBuilder {
    
    private final boolean isFirst;
    private final StringBuilder val;
    private final List<String> params;
    
    
    /**
     * Constructs a {@code SegmentBuilder}, initialized with one segment.
     * 
     * @param  segment
     *           at least one segment must be provided
     * 
     * @param  isFirst
     *           {@code true} if this is the first segment of the route,
     *           otherwise {@code false}
     * 
     * @throws NullPointerException
     *           as documented in the javadoc of {@linkplain RouteBuilder}
     * 
     * @throws IllegalArgumentException
     *           as documented in the javadoc of {@linkplain RouteBuilder}
     */
    SegmentBuilder(String segment, boolean isFirst) {
        this.isFirst = isFirst;
        this.val     = new StringBuilder();
        this.params  = new ArrayList<>();
        
        addSegment(segment, isFirst);
    }
    
    /**
     * Unless parameters have already been added, concatenate an ongoing segment
     * with the specified {@code segment}.
     * 
     * @param segment  the extension
     *
     * @throws IllegalStateException
     *           if parameters have already been added
     *
     * @throws NullPointerException
     *           as documented in the javadoc of {@linkplain RouteBuilder}
     *
     * @throws IllegalArgumentException
     *           as documented in the javadoc of {@linkplain RouteBuilder}
     */
    void concat(String segment) {
        if (!params.isEmpty()) {
            throw new IllegalStateException();
        }
        
        addSegment(segment, false);
    }
    
    /**
     * Add a parameter.
     * 
     * @implNote
     * This implementation assumes no responsibility for duplicated parameter
     * names. This method takes the string value at face value. It is the
     * responsibility of the {@code RouteBuilder} to ensure parameter names are
     * unique on a higher level across the entire route.
     * 
     * @param param  the parameter
     *
     * @throws NullPointerException
     *           as documented in the javadoc of {@linkplain RouteBuilder}
     */
    void addParam(String param) {
        requireNonNull(param);
        params.add(param);
    }
    
    /**
     * Returns {@code true} if this builder has parameters added, otherwise
     * {@code false}.
     * 
     * @return {@code true} if this builder has parameters added,
     *         otherwise {@code false}
     */
    boolean hasParams() {
        return !params.isEmpty();
    }
    
    /**
     * Build this builder into a {@code Segment}.
     * 
     * @return a new Segment
     */
    Segment build() {
        return new DefaultSegment(isFirst, val.toString(), params);
    }
    
    private void addSegment(String segment, boolean singleSlashOkay) {
        requireFirstCharIsSlash(segment);
        
        if (!singleSlashOkay) {
            requireNotEqualsOneSlash(segment);
        }
        
        requireNotEqualsTwoSlashes(segment);
        final String washed = tryRemoveSlashEnding(segment, false);
        val.append(washed);
    }
    
    private static void requireFirstCharIsSlash(String segment) {
        if (!segment.startsWith("/")) {
            throw new IllegalArgumentException(
                    "A segment must start with a \"/\" character. Got: \"" + segment + "\"");
        }
    }
    
    private static void requireNotEqualsOneSlash(String segment) {
        if (segment.equals("/")) {
            throw new IllegalArgumentException(
                    "Segment must contain more than just a forward slash.");
        }
    }
    
    private static void requireNotEqualsTwoSlashes(String segment) {
        if (segment.equals("//")) {
            throw new IllegalArgumentException(
                    "Segment must contain more than just forward slash(es).");
        }
    }
    
    private static String tryRemoveSlashEnding(final String path, boolean all) {
        if (path.length() < 2 || !path.endsWith("/")) {
            return path;
        }
        
        final String cut = path.substring(0, path.length() - 1);
        
        // If the cut still has a trailing '/', maybe remove them too;
        //   1) For developers we don't - no magic!
        //   2) For input from HTTP we must be tolerant, so we do.
        if (cut.length() > 1 && cut.endsWith("/")) {
            if (all) {
                // TODO: Unroll into a while-loop or something instead of recursion.
                return tryRemoveSlashEnding(cut, true);
            }
            else {
                throw new IllegalArgumentException(
                        "Multiple trailing forward slashes in segment: \"" + path + "\"");
            }
        }
        
        return cut;
    }
}