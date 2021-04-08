package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;

import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

/**
 * An HTTP message contains a body when no body is permitted.<p>
 * 
 * Is thrown by the server if a {@link HttpConstants.Method#TRACE} request
 * contains a body. Is thrown by {@link Response.Builder#body(Flow.Publisher)}
 * if the response status-code is 1xx. Is thrown by the server if a response
 * body publisher publishes a bytebuffer in response to a {@link
 * HttpConstants.Method#HEAD} request.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpServer
 * @see ErrorHandler
 */
public class IllegalBodyException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final Object obj;
    
    /**
     * Constructs an {@code IllegalBodyException}.
     * 
     * @param message passed through to {@link Throwable#Throwable(String)}
     * @param request the offending message
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public IllegalBodyException(String message, Request request) {
        this(message, (Object) request);
    }
    
    /**
     * Constructs an {@code IllegalBodyException}.
     * 
     * @param message passed through to {@link Throwable#Throwable(String)}
     * @param response the offending message
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public IllegalBodyException(String message, Response response) {
        this(message, (Object) response);
    }
    
    private IllegalBodyException(String message, Object obj) {
        super(message);
        this.obj = requireNonNull(obj);
    }
    
    /**
     * Returns the offending HTTP message; either a {@link Request} or a {@link
     * Response}.
     * 
     * @return a {@code Request} or a {@code Response} (never {@code null})
     */
    final Object getObject() {
        return obj;
    }
}