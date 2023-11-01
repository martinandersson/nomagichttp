package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.HasResponse;

import java.io.Serial;

import static alpha.nomagichttp.message.Responses.badRequest;

/**
 * A generic exception to reject a bad request.<p>
 * 
 * This exception is thrown by the server in the following cases:
 * <ul>
 *   <li>Both Content-Length and Transfer-Encoding is present in the headers.</li>
 *   <li>Transfer-Encoding contains multiple "chunked" tokens.</li>
 * </ul>
 * 
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.3">RFC 7230 §3.3.3 paragraph 3</a>
 */
public final class BadRequestException
             extends RuntimeException implements HasResponse
{
    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code BadRequestException}.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     */
    public BadRequestException(String message) {
        super(message);
    }
    
    /**
     * {@return {@link Responses#badRequest()}}
     */
    @Override
    public Response getResponse() {
        return badRequest();
    }
}
