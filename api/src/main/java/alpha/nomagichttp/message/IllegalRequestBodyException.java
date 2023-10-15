package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ExceptionHandler;
import alpha.nomagichttp.handler.HasResponse;

import java.io.Serial;

import static alpha.nomagichttp.message.Responses.badRequest;
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
 * @see ExceptionHandler
 */
public final class IllegalRequestBodyException
             extends RuntimeException implements HasResponse
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    private final transient RawRequest.Head head;
    private final transient Request.Body body;
    
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
    
    /**
     * Returns {@link Responses#badRequest()}.
     * 
     * @return see Javadoc
     */
    @Override
    public Response getResponse() {
        return badRequest();
    }
}
