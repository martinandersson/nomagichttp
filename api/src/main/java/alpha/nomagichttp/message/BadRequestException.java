package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.ErrorHandler;

/**
 * A generic exception to reject a bad request.<p>
 * 
 * This exception is thrown by the server in the following cases:
 * <ul>
 *   <li>Both Content-Length and Transfer-Encoding is present in the headers.</li>
 *   <li>Transfer-Encoding contains multiple "chunked" tokens.</li>
 * </ul>
 * 
 * The {@link ErrorHandler#BASE base error handler} translates this
 * exception to a 400 (Bad Request) response.
 * 
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.3">RFC 7230 §3.3.3 paragraph 3</a>
 */
public class BadRequestException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a {@code BadRequestException}.
     * 
     * @param message passed as-is to {@link Throwable#Throwable(String)}
     */
    public BadRequestException(String message) {
        super(message);
    }
}