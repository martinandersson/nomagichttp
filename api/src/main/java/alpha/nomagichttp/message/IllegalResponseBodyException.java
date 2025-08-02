package alpha.nomagichttp.message;

import alpha.nomagichttp.ChannelWriter;
import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ExceptionHandler;
import alpha.nomagichttp.handler.HasResponse;

import java.io.Serial;

import static alpha.nomagichttp.message.Responses.internalServerError;
import static java.util.Objects.requireNonNull;

/// A response has a body when none was expected.
/// 
/// Is thrown by [Response.Builder#build()] if the body is not knowingly
/// [empty][ResourceByteBufferIterable#isEmpty()] and the status code is one of
/// 1XX (Informational), 204 (No Content), 304 (Not Modified).
/// 
/// The exception is also thrown by [ChannelWriter#write(Response)] for the same
/// body-conditional status codes, but also if the request — to which the
/// response is a response — has HTTP method [HEAD][HttpConstants.Method#HEAD].
/// 
/// The former is a fail-fast mechanism. But the request's HTTP method can only
/// be checked during a live HTTP exchange.
/// 
/// @see HttpServer
/// @see ExceptionHandler
/// @see <a href="https://datatracker.ietf.org/doc/html/rfc9112#section-6.3">RFC 9112 §6.3</a></a>
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
