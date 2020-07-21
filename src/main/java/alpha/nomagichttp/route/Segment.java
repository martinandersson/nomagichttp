package alpha.nomagichttp.route;

import java.util.List;
import java.util.RandomAccess;

/**
 * Represents an immutable segment of a route.<p>
 * 
 * The only reason why a route may need to split up into segments is if the
 * route has declared path parameters. Path parameters "belong" to the segment
 * declared right before the parameters.<p>
 * 
 * For example, route "/abc/{param1}/def/{param2}" consists of two segments.
 * The first segment "/abc" is associated with "param1" and segment "/def" is
 * associated with "param2".<p>
 * 
 * Route "/{param1}/{param2}" has only one segment ("/") with two parameters.<p>
 * 
 * Implementations must <i>not</i> override {@code hashCode()} and
 * {@code equals()}. Segments are identity-based.<p>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see SegmentBuilder
 */
interface Segment
{
    /**
     * Returns {@code true} if this segment is the first segment of the route,
     * otherwise {@code false}.<p>
     * 
     * The first segment could also be the <i>only</i> part of a route.<p>
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
}