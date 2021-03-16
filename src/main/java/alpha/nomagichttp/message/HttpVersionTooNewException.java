package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;

/**
 * Thrown by the server if the request HTTP version is not supported, because it
 * is too new.<p>
 * 
 * Any HTTP version equal to or greater than HTTP/2 is too new.<p>
 * 
 * Most likely, the client request will start as an HTTP/1.1 request with an
 * {@value HttpConstants.HeaderKey#UPGRADE} set, which is currently ignored by
 * the NoMagicHTTP server, and therefore the request will not upgrade but keep
 * running on HTTP/1.1 instead of crashing. This exception will only be observed
 * if the client <i>begin</i> a new exchange using HTTP/2. Future work will add
 * support for HTTP/2.<p>
 * 
 * The {@link ErrorHandler#DEFAULT default error handler} will translate this
 * exception to a {@link Responses#httpVersionNotSupported() 505 HTTP Version
 * Not Supported}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpServer
 * @see HttpConstants.Version
 */
public class HttpVersionTooNewException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final String version;
    
    /**
     * Constructs a {@code HttpVersionTooNewException}.
     *
     * @param httpVersion rejected
     * @throws NullPointerException if {@code version} is {@code null}
     */
    public HttpVersionTooNewException(String httpVersion) {
        version = requireNonNull(httpVersion);
    }
    
    /**
     * Returns the rejected version.<p>
     * 
     * E.g. "HTTP/999".
     * 
     * @return the rejected version (never {@code null})
     */
    public final String version() {
        return version;
    }
}