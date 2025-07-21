package alpha.nomagichttp.message;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ExceptionHandler;
import alpha.nomagichttp.handler.HasResponse;

import java.io.Serial;

import static alpha.nomagichttp.message.Responses.internalServerError;
import static java.util.Objects.requireNonNull;

/**
 * A response has a body when none was expected.<p>
 * 
 * Is thrown from {@link Response.Builder#build()} if the response has a body,
 * but the status-code is 1XX (Informational), 204 (No Content), or 304
 * (Not Modified).<p>
 * 
 * The exception is also thrown from {@link ChannelWriter#write(Response)} for
 * the same reasons, but additionally in the case the response has a body and
 * the request method — to which the response is a response — has HTTP method.
 * <p>
 * 
 * The former is a fail-fast mechanism. But the request's HTTP method can only
 * be checked during a live HTTP exchange.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpServer
 * @see ExceptionHandler
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc9112#section-6.3">RFC 9112 §6.3</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc9110#section-9.3.2">RFC 9110 §9.3.2</a>
 */
public final class IllegalResponseBodyException
             extends RuntimeException implements HasResponse
{
    @Serial
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
     * {@return the response which was illegal}
     */
    Response getIllegal() {
        return illegal;
    }
    
    /**
     * {@return {@link Responses#internalServerError()}}
     */
    @Override
    public Response getResponse() {
        return internalServerError();
    }
}
