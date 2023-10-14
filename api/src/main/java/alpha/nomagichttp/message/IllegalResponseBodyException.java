package alpha.nomagichttp.message;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.HasResponse;

import static alpha.nomagichttp.message.Responses.internalServerError;
import static java.util.Objects.requireNonNull;

/**
 * A response has a body when none was expected.<p>
 * 
 * Is thrown from {@link Response.Builder#build()} if the response has a body,
 * but the status-code is 1XX (Informational), 204 (No Content), or 304
 * (Not Modified) (
 * <a href="https://tools.ietf.org/html/rfc7230#section-3.3.3">RFC 7230 §3.3.3</a>
 * ).<p>
 * 
 * The exception is also thrown from {@link ChannelWriter#write(Response)} for
 * the same reasons, but additionally in the case the response has a body and
 * the request method — to which the response is a response — has HTTP method
 * {@link HttpConstants.Method#HEAD HEAD} (
 * <a href="https://tools.ietf.org/html/rfc7231#section-4.3.2">RFC 7231 §4.3.8</a>
 * ).<p>
 * 
 * The former is a fail-fast mechanism. But the request's HTTP method can only
 * be checked during a live HTTP exchange.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpServer
 * @see ErrorHandler
 */
public final class IllegalResponseBodyException
             extends RuntimeException implements HasResponse
{
    private static final long serialVersionUID = 1L;
    
    private final transient Response illegal;
    
    /**
     * Constructs this object.
     * 
     * @param message passed through to {@link Throwable#Throwable(String)}
     * @param response the offending message
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public IllegalResponseBodyException(String message, Response response) {
        super(message);
        illegal = requireNonNull(response);
    }
    
    /**
     * Returns the response which was illegal.
     * 
     * @return see Javadoc
     */
    Response getIllegal() {
        return illegal;
    }
    
    /**
     * Returns {@link Responses#internalServerError()}.
     * 
     * @return see Javadoc
     */
    @Override
    public Response getResponse() {
        return internalServerError();
    }
}
