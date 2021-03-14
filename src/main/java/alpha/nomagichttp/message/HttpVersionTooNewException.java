package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;

/**
 * Thrown by the server if the request HTTP version is not supported, because it
 * is too new.<p>
 * 
 * Currently, HTTP/2 is not supported, but will not crash the exchange. The HTTP
 * Upgrade header will simply be ignored and the exchange will continue using
 * HTTP/1.1.<p>
 * 
 * The {@link ErrorHandler#DEFAULT default error handler} will translate this
 * exception to a {@link Responses#httpVersionNotSupported() 505 HTTP Version
 * Not Supported}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
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