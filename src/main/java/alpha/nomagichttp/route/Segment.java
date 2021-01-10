package alpha.nomagichttp.route;

import java.util.List;
import java.util.RandomAccess;

/**
 * Is a segment of a route's path; essentially just a string (the segment value)
 * with optional path parameters.<p>
 * 
 * Segments are immutable and thread-safe.<p>
 * 
 * Segments are identity based. The implementation does <i>not</i> override
 * {@code hashCode()} and {@code equals()}.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
interface Segment
{
    /**
     * Creates a {@link Segment.Builder}.
     * 
     * @param str      the initial {@link #value() string value} (a segment can
     *                 not be empty)
     * @param isFirst  {@code true} if the segment being built is the first
     *                 segment of the route
     * 
     * @throws NullPointerException
     *           if {@code str} is {@code null}
     * 
     * @throws IllegalArgumentException
     *           if {@code str} is not valid, see {@linkplain Route.Builder}
     */
    @Deprecated // To be removed
    static Segment.Builder builder(String str, boolean isFirst) {
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
    @Deprecated // To be removed
    boolean isFirst();
    
    /**
     * Returns the string value of this segment without a leading forward slash
     * ('/') character.<p>
     * 
     * The first segment of a route (the root) is the only segment allowed to
     * have a value which is the empty string. All other segments must have
     * contents.<p>
     * 
     * For example, the route "/a/b/c".{@linkplain Route#segments() segments()}
     * will yield all the following segment values: "", "a", "b" and "c".
     * 
     * @return the string value of this segment
     */
    String value();
    
    /**
     * Returns an unmodifiable list of path parameter names associated with this
     * segment. Each name returned is unique not just for this segment but also
     * for the route to which the segment belongs.<p>
     * 
     * The returned list implements {@link RandomAccess}.
     * 
     * @return an unmodifiable list of path parameter names declared on this
     * segment
     */
    List<String> params();
    
    /**
     * Returns the {@linkplain #value()} prefixed with '/' followed by parameter
     * names (if present) enclosed in curly brackets and prefixed with '/'. For
     * example, "/segment/{param-name}".
     * 
     * @return see Javadoc
     */
    @Override
    String toString();
    
    /**
     * Builder of {@link Segment}.<p>
     * 
     * The builder is not thread-safe and is intended to be used as a throw-away
     * object.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    @Deprecated // To be removed
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
         *           if {@code str} is {@code null}
         * 
         * @throws IllegalArgumentException
         *           if {@code str} is not valid, see {@linkplain Route.Builder}
         */
        void append(String str);
        
        /**
         * Declare a path parameter.
         * 
         * The segment builder can not guard against duplicated parameter names
         * in the route. This is the responsibility of the {@link Route.Builder}.
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
         * 
         * @throws IllegalStateException if already built
         */
        Segment build();
    }
}