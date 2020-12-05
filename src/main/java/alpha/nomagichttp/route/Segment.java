package alpha.nomagichttp.route;

import java.util.List;
import java.util.RandomAccess;

/**
 * Is a segment of a route's path.<p>
 * 
 * The route's path may be comprised of one or multiple segments. What makes the
 * difference is path parameters which finalizes the segment they belong to. If
 * the route's path continues being built after a path parameter has been
 * declared then the remainder of the path belongs to one or more other
 * segments. The types {@code Segment} and {@code Segment.Builder} are technical
 * details that is not visible to the application developer when using {@link
 * Route#newBuilder()}.<p>
 * 
 * Segments are immutable and thread-safe.<p>
 * 
 * Segments are identity based. Implementations must <i>not</i> override {@code
 * hashCode()} and {@code equals()}.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see Segment.Builder
 */
interface Segment
{
    /**
     * Creates a {@link Segment.Builder}.<p>
     * 
     * As long as path parameters have <i>not</i> been declared, the segment can
     * keep being extended using {@link Segment.Builder#append(String)}.
     * 
     * @param value    the initial piece (a segment can not be empty)
     * @param isFirst  {@code true} if the segment being built is the first
     *                 segment of the route
     * 
     * @throws NullPointerException
     *           if {@code value} is {@code null}
     * 
     * @throws IllegalArgumentException
     *           if {@code value} is not valid, see {@linkplain RouteBuilder}
     */
    static Segment.Builder newBuilder(String value, boolean isFirst) {
        return new DefaultSegment.Builder(value, isFirst);
    }
    
    /**
     * Returns {@code true} if this segment is the first segment of the route,
     * otherwise {@code false}.<p>
     * 
     * The first segment of the route could also be the <i>only</i> part of a
     * route.<p>
     * 
     * @return {@code true} if this segment is the first segment of the route,
     *         otherwise {@code false}
     */
    boolean isFirst();
    
    /**
     * Returns the value of this segment.<p>
     * 
     * The first segment is the only one with a value allowed to be only a
     * forward slash ('/') character. The value of subsequent segments will have
     * more characters.<p>
     * 
     * The value always starts with '/'. Segments that are not the first segment
     * of the route also never ends with '/'.<p>
     * 
     * Examples of valid segment values:
     * <pre>
     *   /
     *   /abc
     *   /def/xyz
     * </pre>
     * 
     * Examples of invalid values (which this method never returns):
     * <pre>
     *   ""     (the empty String)
     *   " "    (a blank String
     *   //     (effectively a blank segment)
     *   /abc/  (value never ends with a forward slash)
     * </pre>
     * 
     * @return segment value (never {@code null} or the empty string)
     */
    String value();
    
    /**
     * Returns an unmodifiable list of path parameters associated with this
     * segment.<p>
     * 
     * The returned list implements {@link RandomAccess}.
     * 
     * @return an unmodifiable list of path parameters associated with this segment
     */
    List<String> params();
    
    /**
     * Returns the segment value followed by parameter names enclosed in curly
     * brackets. For example, "/segment/{param-name}".
     * 
     * @return the segment value followed by parameter names
     */
    @Override
    String toString();
    
    /**
     * Builder of {@link Segment}.<p>
     * 
     * The implementation does not have to be thread-safe or implement any of
     * {@code hashCode()} and {@code equals()}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Builder
    {
        /**
         * Expand this segment with an additional piece.
         * 
         * @param value of the extension
         * 
         * @throws IllegalStateException
         *           if parameters have been added
         * 
         * @throws NullPointerException
         *           if {@code value} is {@code null}
         * 
         * @throws IllegalArgumentException
         *           if {@code value} is not valid, see {@linkplain RouteBuilder}
         */
        void append(String value);
        
        /**
         * Declare a parameter.
         * 
         * The segment builder can not guard against duplicated parameter names
         * in the route. This is the responsibility of the {@link RouteBuilder}.
         * 
         * @param name parameter name
         * 
         * @throws NullPointerException if {@code name} is {@code null}
         */
        void addParam(String name);
        
        /**
         * Returns {@code true} if this builder has parameters declared,
         * {@code false} otherwise.
         *
         * @return {@code true} if this builder has parameters added,
         *         {@code false} otherwise
         */
        boolean hasParams();
        
        /**
         * Build a new {@code Segment}.
         * 
         * @return a new {@code Segment}
         */
        Segment build();
    }
}