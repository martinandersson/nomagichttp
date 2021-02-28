package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.RequestHandler;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.Headers;

/**
 * Thrown by {@link Headers} when attempting to convert a String header value
 * into another Java type.<p>
 * 
 * When observed by a route-level error handler, the {@link Route} and {@link
 * Request} arguments will be non-null, but the {@link RequestHandler} argument
 * may or may not be null.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 */
public class BadHeaderException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code BadHeaderException}.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     */
    public BadHeaderException(String message) {
        super(message);
    }
    
    /**
     * Constructs a {@code BadHeaderException}.
     * 
     * @param message  passed as-is to {@link Throwable#Throwable(String)}
     * @param cause    passed as-is to {@link Throwable#Throwable(String, Throwable)}
     */
    public BadHeaderException(String message, Throwable cause) {
        super(message, cause);
    }
}