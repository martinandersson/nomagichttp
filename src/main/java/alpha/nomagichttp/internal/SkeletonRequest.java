package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.util.Attributes;

/**
 * A thin version of a request.<p>
 * 
 * This version is constructed at an early stage of the HTTP exchange. Apart
 * from entries in the contained attributes, the instance itself is immutable
 * and remains the same throughout the exchange.
 * 
 * @author Martin Andersson (webmaster@martinandersson.com)
 * @see DefaultRequest
 */
final class SkeletonRequest
{
    private final RequestHead h;
    private final SkeletonRequestTarget t;
    private final RequestBody b;
    private final Attributes a;
    
    /**
     * Constructs this object.
     * 
     * @param h request head
     * @param t request target
     * @param b request body
     * @param a request attributes
     */
    SkeletonRequest(RequestHead h, SkeletonRequestTarget t,
                    RequestBody b, Attributes a)
    {
        assert h != null;
        assert t != null;
        assert b != null;
        assert a != null;
        this.h = h;
        this.t = t;
        this.b = b;
        this.a = a;
    }
    
    /**
     * Returns the request head.
     * 
     * @return the request head (never {@code null}
     */
    RequestHead head() {
        return h;
    }
    
    /**
     * Returns the request target.
     * 
     * @return the request target (never {@code null}
     */
    SkeletonRequestTarget target() {
        return t;
    }
    
    /**
     * Returns the request body.
     * 
     * @return the request body (never {@code null}
     */
    RequestBody body() {
        return b;
    }
    
    /**
     * Returns the request attributes.
     * 
     * @return the request attributes (never {@code null})
     */
    Attributes attributes() {
        return a;
    }
}