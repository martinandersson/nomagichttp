package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.ErrorHandler;

import static java.util.Objects.requireNonNull;

/**
 * Thrown by the server if the request HTTP version is not supported, because it
 * is too old.<p>
 * 
 * Any HTTP version below 1.0 is too old.<p>
 * 
 * The {@link ErrorHandler#BASE base error handler} will translate this
 * exception to a {@link Responses#upgradeRequired(String) 426 Upgrade Required}.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpServer
 * @see Config#minHttpVersion()
 */
public class HttpVersionTooOldException extends RuntimeException
{
    private static final long serialVersionUID = 1L;
    
    private final String version, upgrade;
    
    /**
     * Constructs an {@code HttpVersionTooOldException}.
     * 
     * @param httpVersion rejected
     * @param upgrade server's proposed version
     * 
     * @throws NullPointerException
     *             if any argument is {@code null}
     */
    public HttpVersionTooOldException(String httpVersion, Version upgrade) {
        this(httpVersion, upgrade, null);
    }
    
    /**
     * Constructs an {@code HttpVersionTooOldException}.
     * 
     * @param httpVersion rejected
     * @param upgrade server's proposed version
     * @param cause what caused this exception (may be {@code null})
     * 
     * @throws NullPointerException
     *             if {@code httpVersion} or {@code upgrade} is {@code null}
     */
    public HttpVersionTooOldException(String httpVersion, Version upgrade, Throwable cause) {
        super(cause);
        this.version = requireNonNull(httpVersion);
        this.upgrade = upgrade.toString();
    }
    
    /**
     * Returns the rejected version.<p>
     * 
     * E.g. "HTTP/0.9".
     * 
     * @return the rejected version (never {@code null})
     */
    public final String getVersion() {
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
    public String getUpgrade() {
        return upgrade;
    }
}