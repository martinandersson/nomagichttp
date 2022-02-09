package alpha.nomagichttp.message;

import alpha.nomagichttp.handler.ErrorHandler;

/**
 * A generic exception to reject a bad request.<p>
 * 
 * This exception is thrown by the server if both Content-Length and
 * Transfer-Encoding is present in the headers.<p>
 * 
 * This exception translates to a 400 (Bad Request) response by the {@link
 * ErrorHandler#DEFAULT default error handler}. There are other exceptions as
 * well, that translates to a 400 (Bad Request) response, such as unacceptable
 * values of the previously mentioned headers.
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