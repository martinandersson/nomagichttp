package alpha.nomagichttp.message;

import alpha.nomagichttp.Config;
import alpha.nomagichttp.HttpServer;
import alpha.nomagichttp.handler.HasResponse;

import java.io.Serial;

import static alpha.nomagichttp.message.Responses.httpVersionNotSupported;
import static java.util.Objects.requireNonNull;

/**
 * Thrown by the server if the request HTTP version is not supported, because it
 * is too new.<p>
 * 
 * Any HTTP version equal to or greater than HTTP/2 is too new.<p>
 * 
 * HTTP/2 requires the client to have prior knowledge of HTTP/2 support, or
 * negotiate HTTP/2 through the TLS ALPN mechanism
 * (RFC 9113 §3 "Starting HTTP/2"). Or in other words, this exception should
 * never be observed.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com)
 * 
 * @see HttpServer
 * @see Config#minHttpVersion()
 */
public final class HttpVersionTooNewException
             extends RuntimeException implements HasResponse
{
    @Serial
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
    public String getVersion() {
        return version;
    }
    
    /**
     * {@return {@link Responses#httpVersionNotSupported()}}
     */
    @Override
    public Response getResponse() {
        return httpVersionNotSupported();
    }
}
