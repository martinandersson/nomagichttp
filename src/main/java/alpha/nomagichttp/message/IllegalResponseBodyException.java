package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;

/**
 * Is thrown:
 * <ul>
 *   <li>By {@link Response.Builder#build()} if the response status-code is 1XX
 *       (Informational), or 204 (No Content), or 304 (Not Modified) - and, the
 *       response presumably has a body (
 *       <a href="https://tools.ietf.org/html/rfc7230#section-3.3.3">RFC 7230 §3.3.3</a>).</li>
 *   <li>By the server if a response body publisher publishes a bytebuffer in
 *       response to a {@link HttpConstants.Method#HEAD HEAD}
 *       (<a href="https://tools.ietf.org/html/rfc7231#section-4.3.2">RFC 7231 §4.3.8</a>)
 *       or {@link HttpConstants.Method#CONNECT CONNECT} (
 *       <a href="https://tools.ietf.org/html/rfc7231#section-4.3.6">RFC 7231 §4.3.6</a>)
 *       request.</li>
 * </ul>
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see ErrorHandler
 */
public class IllegalResponseBodyException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final Response response;
    
    /**
     * Constructs this object.
     * 
     * @param message passed through to {@link Throwable#Throwable(String)}
     * @param response the offending message
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public IllegalResponseBodyException(String message, Response response) {
        super(message);
        this.response = requireNonNull(response);
    }
    
    /**
     * Returns the response object.
     * 
     * @return the response object (never {@code null})
     */
    public Response response() {
        return response;
    }
}
