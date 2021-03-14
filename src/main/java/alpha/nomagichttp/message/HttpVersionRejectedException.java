package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;

/**
 * Thrown by the server if the request HTTP version is not supported and there
 * is no way to continue the HTTP exchange.<p>
 * 
 * Currently, HTTP/2 is not supported, but will not crash the exchange. The HTTP
 * Upgrade header will simply be ignored and the exchange will continue using an
 * older protocol version.<p>
 * 
 * The {@link ErrorHandler#DEFAULT default error handler} will translate this
 * exception to a {@link Responses#upgradeRequired() 426 Upgrade Required}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpConstants.Version
 */
public class HttpVersionRejectedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final HttpConstants.Version ver;
    
    /**
     * Constructs a {@code HttpVersionRejectedException}.
     * 
     * @param version rejected
     * @throws NullPointerException if {@code version} is {@code null}
     */
    public HttpVersionRejectedException(HttpConstants.Version version) {
        ver = requireNonNull(version);
    }
    
    /**
     * Returns the rejected version.
     * 
     * @return the rejected version (never {@code null})
     */
    public final HttpConstants.Version version() {
        return ver;
    }
}