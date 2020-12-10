package alpha.nomagichttp.route;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.text.MessageFormat.format;

/**
 * Default implementation of {@link RouteRegistry}.
 *
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class DefaultRouteRegistry implements RouteRegistry
{
    /** Does not allow null keys or values. */
    private final ConcurrentMap<String, Route> map = new ConcurrentHashMap<>();
    
    @Override
    public void add(Route route) {
        Route old;
        if ((old = map.putIfAbsent(route.identity(), route)) != null) {
            throw new RouteCollisionException(format(
                    "The specified route \"{0}\" is equivalent to an already added route \"{1}\".",
                    route, old));
        }
    }
    
    @Override
    public Route remove(String id) {
        return map.remove(id);
    }
    
    @Override
    public Route.Match lookup(String requestTarget) {
        int query = requestTarget.indexOf('?');
        
        // Strip the query part
        String rt = query == -1 ?
                requestTarget : requestTarget.substring(0, query);
        
        // TODO: A performance improvement would be to memoize a tree of segments.
        //       Request comes in, its target is immediately split into segments
        //       which are provided to lookup(), then we traverse the tree to find
        //       the route. Type Segment would probably have to be made public and
        //       Route exposes all his segments. This would also make the type
        //       system easier to understand. Route.matches() is basically an
        //       algorithm embedded into what otherwise could be a simple value
        //       type of segments (which it sort of is already).
        
        Optional<Route.Match> m = map.values().stream()
                .map(r -> r.matches(rt))
                .filter(Objects::nonNull)
                .findAny();
        
        return m.orElseThrow(() -> new NoRouteFoundException(requestTarget));
    }
}