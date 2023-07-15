package alpha.nomagichttp.route;

import alpha.nomagichttp.handler.RequestHandler;

/**
 * Thrown by {@link Route.Builder#handler(RequestHandler, RequestHandler...)} if
 * two or more handlers are equivalent to each other.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
// TODO: Make non-final (as-is other public exception types referenced by a public interface)
public final class HandlerCollisionException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    HandlerCollisionException(String message) {
        super(message);
    }
}
