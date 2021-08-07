package alpha.nomagichttp.internal;

import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.route.NoRouteFoundException;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.route.RouteCollisionException;
import alpha.nomagichttp.route.RouteRegistry;
import alpha.nomagichttp.route.SegmentsBuilder;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static alpha.nomagichttp.internal.Segments.ASTERISK_CH;
import static alpha.nomagichttp.internal.Segments.ASTERISK_STR;
import static alpha.nomagichttp.internal.Segments.COLON_CH;
import static alpha.nomagichttp.internal.Segments.COLON_STR;
import static alpha.nomagichttp.internal.Segments.noParamNames;
import static java.text.MessageFormat.format;

/**
 * Default implementation of {@link RouteRegistry}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
final class DefaultRouteRegistry implements RouteRegistry
{
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
                parent.hasNoChild(COLON_STR) && parent.hasNoChild(ASTERISK_STR));
        
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
    
    /**
     * Match the path segments from a request path against a route.
     * 
     * @param rt request target
     * 
     * @return a match (never {@code null})
     * 
     * @throws NullPointerException
     *             if {@code rt} is {@code null}
     * @throws NoRouteFoundException
     *             if a route can not be found
     */
    ResourceMatch<Route> lookup(RequestTarget rt) {
        Iterable<String> dec = rt.segmentsPercentDecoded();
        
        Tree.ReadNode<Route> n = findNodeFromSegments(dec);
        
        if (n == null) {
            throw new NoRouteFoundException(dec);
        }
        
        // check node's value for a route
        Route r;
        if ((r = n.get()) != null) {
            return ResourceMatch.of(rt, r, r.segments());
        }
        
        // nothing was there? check for a catch-all child
        n = n.next(ASTERISK_STR);
        
        if (n == null) {
            throw new NoRouteFoundException(dec);
        }
        
        if ((r = n.get()) != null) {
            return ResourceMatch.of(rt, r, r.segments());
        }
        
        throw new NoRouteFoundException(dec);
    }
    
    private Tree.ReadNode<Route> findNodeFromSegments(Iterable<String> decoded) {
        Tree.ReadNode<Route> n = tree.read();
        for (String s : decoded) {
            Tree.ReadNode<Route> c = n.next(s);
            if (c != null) {
                // Static segment found, on to next
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