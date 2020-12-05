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
     * @param str      the initial {@link #value() string value} (a segment can
     *                 not be empty)
     * @param isFirst  {@code true} if the segment being built is the first
     *                 segment of the route
     * 
     * @throws NullPointerException
     *           if {@code value} is {@code null}
     * 
     * @throws IllegalArgumentException
     *           if {@code value} is not valid, see {@linkplain RouteBuilder}
     */
    static Segment.Builder newBuilder(String str, boolean isFirst) {
        return new DefaultSegment.Builder(str, isFirst);
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
     * Returns the string value of this segment.<p>
     * 
     * The first segment of a route is the only segment with a string value
     * allowed to be <i>only</i> a single forward slash ('/') character.<p>
     * 
     * The string value always start with '/'. Non-first segments never ends
     * with '/'.<p>
     * 
     * A few examples of valid string values:
     * <pre>
     *   /
     *   /abc
     *   /def/xyz
     * </pre>
     * 
     * Examples of invalid string values (never returned by this method):
     * <pre>
     *   ""     (the empty string)
     *   " "    (a blank string)
     *   //     (effectively a blank segment)
     *   /abc/  (ends with a forward slash)
     * </pre>
     * 
     * @return the string value of this segment
     */
    String value();
    
    /**
     * Returns an unmodifiable list of path parameter names declared on this
     * segment.<p>
     * 
     * The returned list implements {@link RandomAccess}.
     * 
     * @return an unmodifiable list of path parameter names declared on this
     * segment
     */
    List<String> params();
    
    /**
     * Returns the {@linkplain #value() string value} concatenated with declared
     * parameter names enclosed in curly brackets. For example,
     * "/segment/{param-name}".
     * 
     * @return the segment value concatenated with declared parameter names
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
         * Append {@code str} to the segment's current {@linkplain #value()
         * string value}. Operation only allowed if no path parameters have been
         * declared.
         * 
         * @param str value to append
         * 
         * @throws IllegalStateException
         *           if parameters have been declared
         * 
         * @throws NullPointerException
         *           if {@code value} is {@code null}
         * 
         * @throws IllegalArgumentException
         *           if {@code value} is not valid, see {@linkplain RouteBuilder}
         */
        void append(String str);
        
        /**
         * Declare a path parameter.
         * 
         * The segment builder can not guard against duplicated parameter names
         * in the route. This is the responsibility of the {@link RouteBuilder}.
         * 
         * @param name of parameter (any string)
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