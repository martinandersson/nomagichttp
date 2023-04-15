package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;

/**
 * Thrown by the server if the request HTTP version is not supported, because it
 * is too new.<p>
 * 
 * Any HTTP version equal to or greater than HTTP/2 is too new.<p>
 * 
 * HTTP/2 requires the exchange to begin as HTTP/1.1, then upgrades to HTTP/2
 * (RFC 7540 §3 "Starting HTTP/2"), which the NoMagicHTTP server currently
 * doesn't do. And so, this exception should actually never be observed.<p>
 * 
 * The {@link ErrorHandler#BASE base error handler} will translate this
 * exception to a {@link Responses#httpVersionNotSupported() 505 HTTP Version
 * Not Supported}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpServer
 * @see Config#minHttpVersion()
 */
public class HttpVersionTooNewException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final String version;
    
    /**
     * Constructs a {@code HttpVersionTooNewException}.
     *
     * @param httpVersion rejected
     * @param cause what caused this exception (may be {@code null})
     * 
     * @throws NullPointerException
     *             if {@code version} is {@code null}
     */
    public HttpVersionTooNewException(String httpVersion, Throwable cause) {
        super(cause);
        version = requireNonNull(httpVersion);
    }
    
    /**
     * Returns the rejected version.<p>
     * 
     * E.g. "HTTP/999".
     * 
     * @return the rejected version (never {@code null})
     */
    public final String getVersion() {
        return version;
    }
}