package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteCollisionException;
import alpha.nomagichttp.route.RouteRegistry;
import alpha.nomagichttp.route.SegmentsBuilder;
import alpha.nomagichttp.util.PercentDecoder;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.text.MessageFormat.format;
import static java.util.stream.Collectors.toList;

/**
 * Default implementation of {@link RouteRegistry}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRouteRegistry implements RouteRegistry
{
    private static final char COLON_CH = ':',    // key for single- path params
                              ASTERISK_CH = '*'; // key for catch-all path params
    
    private static final String COLON_STR = ":",
                                ASTERISK_STR = "*";
    
    private final HttpServer server;
    
    DefaultRouteRegistry(HttpServer server) {
        this.server = server;
    }
    
    /*
     * Implementation note:
     * 
     * Route objects are stored in the tree as node values. Static segment
     * values will be keyed as-is, single path parameters will be keyed using
     * ":" as key and catch-all path parameters will be keyed using '*'. User
     * provided path parameter names are not stored in the tree, they are
     * irrelevant, it is the route's hierarchical position in the tree that is
     * relevant. The names are only used when constructing the Match object (if
     * there was a match, of course). I.e., route
     * "/user/:id/file/*filepath" will be stored as:
     * 
     *   root -> "user" -> ":" -> "file" -> "*" -> route object
     * 
     * When looking up a route given a request path, it's only a matter of
     * reading the hierarchy using the path segments until we've reached our
     * position. If that position has a route stored, then it's a match.
     */
    
    private final Tree<Route> tree = new Tree<>();
    
    @Override
    public HttpServer add(Route r) {
        Iterator<String> it = r.segments().iterator();
        tree.write(n -> {
            if (!it.hasNext()) {
                // no more segments to traverse, register route in current node
                // (this code is racing with the next synchronized block,
                //    this block be like: don't set a route in current node if there's a catch-all child route; collision between the two
                //    next block is the same thing in reverse, don't set a catch-all child if current has a route
                //    lock (current node) not expected to be contended)
                synchronized (n) {
                    setRouteIfAbsentGiven(thatNoCatchAllChildExist(n), n, r);
                }
                // job done
                return null;
            } else {
                // possibly dig deeper
                final String s = it.next();
                switch (s.charAt(0)) {
                    case COLON_CH:
                        // single path param segment; get- or create exclusive child using key ':'
                        return createExclusiveChild(n, COLON_STR, s);
                    case ASTERISK_CH:
                        assert !it.hasNext();
                        // same, except we use key '*' and set the route immediately
                        synchronized (n) {
                            Tree.WriteNode<Route> c = createExclusiveChild(n, ASTERISK_STR, s);
                            setRouteIfAbsentGiven(thatNodeHasNoRoute(n), c, r);
                        }
                        // job done
                        return null;
                    default:
                        // static segment value; get- or create a normal child using user segment as key
                        return createNormalChild(n, s);
                }
            }
        });
        return server;
    }
    
    private static void setRouteIfAbsentGiven(
            Consumer<Route> someConditionAcceptingRouteValue,
            Tree.WriteNode<Route> target,
            Route newGuy)
    {
        Route oldGuy = target.getAndSetIf(newGuy, v -> {
            if (v != null) {
                // Someone's there already, abort
                return false;
            }
            
            // May fail if the condition doesn't like us to proceed
            someConditionAcceptingRouteValue.accept(newGuy);
            
            // Everything's cool
            return true;
        });
        
        if (oldGuy != null) {
            throw collisionExc(newGuy, oldGuy);
        }
    }
    
    private static Consumer<Route> thatNoCatchAllChildExist(Tree.WriteNode<Route> node) {
        return newGuy -> {
            for (;;) {
                Tree.ReadNode<Route> c = node.getChild(ASTERISK_STR);
                if (c == null) {
                    // Okay, catch-all child does not exist
                    break;
                }
                // The bastard exist, but only a problem if he has a route set
                Route childGuy = c.get();
                if (childGuy != null) {
                    throw collisionExc(newGuy, childGuy);
                }
                // else retry (should happen extremely rarely, but possible)
            }
        };
    }
    
    private static Consumer<Route> thatNodeHasNoRoute(Tree.WriteNode<Route> node) {
        return childGuy -> {
            Route currentGuy = node.get();
            if (currentGuy != null) {
                throw collisionExc(childGuy, currentGuy);
            }
        };
    }
    
    private static Tree.WriteNode<Route> createExclusiveChild(
            Tree.WriteNode<Route> parent, String key, String userSegment)
    {
        Tree.WriteNode<Route> c = parent.nextOrCreateIf(key,
                // Path parameters (by type) are exclusive; can't have any other children
                parent::hasNoChildren);
        
        return requireChildWasCreated(c, userSegment);
    }
    
    private static Tree.WriteNode<Route> createNormalChild(
            Tree.WriteNode<Route> parent, String key)
    {
        Tree.WriteNode<Route> c = parent.nextOrCreateIf(key, () ->
                // Static segment values only blend with each other and doesn't like weirdos
                !parent.hasChild(COLON_STR) && !parent.hasChild(ASTERISK_STR));
        
        return requireChildWasCreated(c, key);
    }
    
    private static Tree.WriteNode<Route> requireChildWasCreated(
            Tree.WriteNode<Route> c, String userSegment)
    {
        if (c == null) {
            throw new RouteCollisionException(
                    "Hierarchical position of \"" + userSegment + "\" is occupied with non-compatible type.");
        }
        return c;
    }
    
    private static RouteCollisionException collisionExc(Route first, Route second) {
        return new RouteCollisionException(format(
                "Route \"{0}\" is equivalent to an already added route \"{1}\".",
                first, second));
    }
    
    @Override
    public Route remove(String pattern) {
        Iterable<String>  seg = new SegmentsBuilder(true).append(pattern).asIterable(),
                          pos = noParamNames(seg);
        return tree.clear(pos);
    }
    
    @Override
    public boolean remove(Route r) {
        Iterable<String> pos = noParamNames(r.segments());
        return tree.clearIf(pos, v -> Objects.equals(v, r)) != null;
    }
    
    private static Iterable<String> noParamNames(Iterable<String> segments) {
        return stream(segments)
                .map(s -> s.startsWith(COLON_STR) ? COLON_STR :
                          s.startsWith(ASTERISK_STR) ? ASTERISK_STR :
                          s)
                .collect(toList());
    }
    
    /**
     * A match of a route.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Match
    {
        /**
         * Returns the matched route.
         * 
         * @return the matched route (never {@code null})
         */
        Route route();
        
        /**
         * Equivalent to {@link Request.Parameters#path(String)}.
         * 
         * @param name of path parameter (case sensitive)
         * @return the path parameter value (percent-decoded)
         */
        String pathParam(String name);
        
        /**
         * Equivalent to {@link Request.Parameters#pathRaw(String)}.
         * 
         * @param name of path parameter (case sensitive)
         * @return the raw path parameter value (not decoded/unescaped)
         */
        String pathParamRaw(String name);
    }
    
    /**
     * Match the path segments from a request path against a route.<p>
     * 
     * The given segments must not be percent-decoded. Decoding is done by the
     * implementation before comparing starts with the segments of registered
     * routes. Both non-decoded and decoded parameter values will be accessible
     * in the returned match object.<p>
     * 
     * Empty strings must be normalized away. The root "/" can be matched by
     * specifying an empty iterable.
     * 
     * @param rawPathSegments from request path (normalized but not percent-decoded)
     * 
     * @return a match (never {@code null})
     * 
     * @throws NullPointerException
     *             if {@code rawPathSegments} is {@code null}
     * @throws IllegalArgumentException
     *             if any encountered segment is the empty string
     * @throws NoRouteFoundException
     *             if a route can not be found
     */
    Match lookup(Iterable<String> rawPathSegments) {
        Iterable<String> decoded = stream(rawPathSegments)
                .map(PercentDecoder::decode)
                .collect(toList());
        
        Tree.ReadNode<Route> n = findNodeFromSegments(decoded);
        
        if (n == null) {
            throw new NoRouteFoundException(decoded);
        }
        
        // check node's value for a route
        Route r;
        if ((r = n.get()) != null) {
            return DefaultMatch.of(r, rawPathSegments, decoded);
        }
        
        // nothing was there? check for a catch-all child
        n = n.next(ASTERISK_STR);
        
        if (n == null) {
            throw new NoRouteFoundException(decoded);
        }
        
        if ((r = n.get()) != null) {
            return DefaultMatch.of(r, rawPathSegments, decoded);
        }
        
        throw new NoRouteFoundException(decoded);
    }
    
    private Tree.ReadNode<Route> findNodeFromSegments(Iterable<String> decoded) {
        Tree.ReadNode<Route> n = tree.read();
        for (String s : decoded) {
            Tree.ReadNode<Route> c = n.next(s);
            if (c != null) {
                // Segment found, on to next
                n = c;
                continue;
            }
            c = n.next(COLON_STR);
            if (c != null) {
                // Segment will be read as value to single path param, on to next
                n = c;
                continue;
            }
            // Catch-all or no match (null), in both cases we're done here
            n = n.next(ASTERISK_STR);
            break;
        }
        return n;
    }
    
    // Package-private only for tests
    static class DefaultMatch implements Match
    {
        private final Route route;
        private final Map<String, String> paramsRaw, paramsDec;
        
        static DefaultMatch of(Route route, Iterable<String> rawSrc, Iterable<String> decSrc) {
            // We need to map "request/path/segments" to "route/:path/*parameters"
            Iterator<String>    decIt  = decSrc.iterator(),
                                segIt  = route.segments().iterator();
            Map<String, String> rawMap = Map.of(),
                                decMap = Map.of();
            
            String catchAllKey = null;
            
            for (String r : rawSrc) {
                 String d = decIt.next();
                
                if (catchAllKey == null) {
                    // Catch-all not activated, consume next route segment
                    String s = segIt.next();
                    
                    switch (s.charAt(0)) {
                        case COLON_CH:
                            // Single path param goes to map
                            String k = s.substring(1),
                                   o = (rawMap = mk(rawMap)).put(k, r);
                            assert o == null;
                                   o = (decMap = mk(decMap)).put(k, d);
                            assert o == null;
                            break;
                        case ASTERISK_CH:
                            // Toggle catch-all phase with this segment as seed
                            catchAllKey = s.substring(1);
                            (rawMap = mk(rawMap)).put(catchAllKey, '/' + r);
                            (decMap = mk(decMap)).put(catchAllKey, '/' + d);
                            break;
                        default:
                            // Static segments we're not interested in
                            break;
                    }
                } else {
                    // Consume all remaining request segments as catch-all
                    rawMap.merge(catchAllKey, '/' + r, String::concat);
                    decMap.merge(catchAllKey, '/' + d, String::concat);
                }
            }
            
            // We're done with the request path, but route may still have a catch-all segment in there
            if (segIt.hasNext()) {
                String s = segIt.next();
                assert s.startsWith("*");
                assert !segIt.hasNext();
                assert catchAllKey == null;
                catchAllKey = s.substring(1);
            }
            
            // We could have toggled to catch-all, but no path segment was consumed for it, and
            if (catchAllKey != null && !rawMap.containsKey(catchAllKey)) {
                // route JavaDoc promises to default with a '/'
                (rawMap = mk(rawMap)).put(catchAllKey, "/");
                (decMap = mk(decMap)).put(catchAllKey, "/");
            }
            
            assert !decIt.hasNext();
            return new DefaultMatch(route, rawMap, decMap);
        }
        
        private static <K, V> Map<K, V> mk(Map<K, V> map) {
            return map.isEmpty() ? new HashMap<>() : map;
        }
        
        private DefaultMatch(Route route, Map<String, String> paramsRaw, Map<String, String> paramsDec) {
            this.route = route;
            this.paramsRaw = paramsRaw;
            this.paramsDec = paramsDec;
        }
        
        @Override
        public Route route() {
            return route;
        }
        
        @Override
        public String pathParam(String name) {
            return paramsDec.get(name);
        }
        
        @Override
        public String pathParamRaw(String name) {
            return paramsRaw.get(name);
        }
        
        /**
         * FOR TESTS ONLY:  Returns the internal map holding raw path parameter
         * values.
         * 
         * @return the internal map holding raw path parameter values
         */
        Map<String, String> mapRaw() {
            return paramsRaw;
        }
        
        Map<String, String> mapDec() {
            return paramsDec;
        }
    }
    
    private static <T> Stream<T> stream(Iterable<T> it) {
        return StreamSupport.stream(it.spliterator(), false);
    }
    
    /**
     * FOR TESTS ONLY: Shortcut for {@link Tree#toMap(CharSequence)} using "/"
     * as key-segment delimiter.
     * 
     * @return all registered routes
     */
    Map<String, Route> dump() {
        return tree.toMap("/");
    }
}