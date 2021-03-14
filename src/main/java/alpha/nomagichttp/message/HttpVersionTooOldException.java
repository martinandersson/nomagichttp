package alpha.nomagichttp.message;

import alpha.nomagichttp.HttpConstants;
import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;

/**
 * Thrown by the server if the request HTTP version is not supported, because it
 * is too old.<p>
 * 
 * The {@link ErrorHandler#DEFAULT default error handler} will translate this
 * exception to a {@link Responses#upgradeRequired(String) 426 Upgrade Required}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpConstants.Version
 */
public class HttpVersionTooOldException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final String version, upgrade;
    
    /**
     * Constructs a {@code HttpVersionTooOldException}.
     * 
     * @param httpVersion rejected
     * @param upgrade server's proposed version
     * @throws NullPointerException if any argument is {@code null}
     */
    public HttpVersionTooOldException(String httpVersion, String upgrade) {
        this.version = requireNonNull(httpVersion);
        this.upgrade = requireNonNull(upgrade);
    }
    
    /**
     * Returns the rejected version.<p>
     * 
     * E.g. "HTTP/0.9".
     * 
     * @return the rejected version (never {@code null})
     */
    public final String version() {
        return version;
    }
    
    /**
     * Returns server's proposed version the client should use instead.<p>
     * 
     * E.g. "HTTP/1.1".
     * 
     * @return server's proposed version the client should use instead
     *         (never {@code null})
     */
    public String upgrade() {
        return upgrade;
    }
}