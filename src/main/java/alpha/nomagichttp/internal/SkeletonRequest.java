package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.RequestHead;
import alpha.nomagichttp.util.Attributes;

/**
 * A container of most of the complex components that a "real" request needs.
 * The missing pieces are HTTP version and resource-specific path parameters.
 * 
 * @author Martin Andersson (webmaster@martinandersson.com)
 */
final class SkeletonRequest
{
    private final RequestHead h;
    private final RequestTarget t;
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
    SkeletonRequest(RequestHead h, RequestTarget t, RequestBody b, Attributes a) {
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
    RequestTarget target() {
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