package alpha.nomagichttp.internal;

import alpha.nomagichttp.message.RequestHead;

/**
 * A request embedding most of the complex components that a "real" request
 * would need. The missing pieces are HTTP version, resource-specific path
 * parameters and exchange-scoped attributes.
 * 
 * @author Martin Andersson (webmaster@martinandersson.com)
 */
final class SkeletonRequest
{
    private final RequestHead h;
    private final RequestTarget t;
    private final RequestBody b;
    
    /**
     * Constructs this object.
     * 
     * @param h request head
     * @param t request target
     * @param b request body
     */
    SkeletonRequest(RequestHead h, RequestTarget t, RequestBody b) {
        assert h != null;
        assert t != null;
        assert b != null;
        this.h = h;
        this.t = t;
        this.b = b;
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
}