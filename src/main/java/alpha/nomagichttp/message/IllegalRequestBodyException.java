package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;

/**
 * Is thrown by the server when a {@link HttpConstants.Method#TRACE} request
 * contains a body.<p>
 * 
 * Please note that this class has no API to retrieve the {@link Request}
 * object, because the request was never valid to begin with and consequently
 * not accepted by the server.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see <a href="https://tools.ietf.org/html/rfc7231#section-4.3.8">RFC 7231 §4.3.8</a>
 * @see HttpServer
 * @see ErrorHandler
 */
public class IllegalRequestBodyException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    private final RawRequest.Head head;
    private final Request.Body body;
    
    /**
     * Initializes this object.
     * 
     * @param message passed through to {@link Throwable#Throwable(String)}
     * @param head of request
     * @param body of request
     * 
     * @throws NullPointerException
     *             if {@code head} or {@code body} is {@code null}
     */
    public IllegalRequestBodyException(RawRequest.Head head, Request.Body body, String message) {
        super(message);
        this.head = requireNonNull(head);
        this.body = requireNonNull(body);
    }
    
    /**
     * Returns the request head.
     * 
     * @return the request head (never {@code null})
     */
    public RawRequest.Head head() {
        return head;
    }
    
    /**
     * Returns the request body.
     *
     * @return the request body (never {@code null})
     */
    public Request.Body body() {
        return body;
    }
}
