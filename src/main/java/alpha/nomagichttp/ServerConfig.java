package alpha.nomagichttp;

import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;

/**
 * Server configuration.<p>
 * 
 * The values are read as late in the process as possible and not cached by the
 * server implementation.<p>
 * 
 * The implementation must be thread-safe.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com
 */
// TODO: Define object identity.
public interface ServerConfig {
    ServerConfig DEFAULT = new ServerConfig(){};
    
    /**
     * Returns the max number of bytes processed while parsing a request
     * head before giving up.<p>
     * 
     * Once the limit has been exceeded, a {@link
     * MaxRequestHeadSizeExceededException} will be thrown.
     * 
     * @implSpec
     * The default implementation is equivalent to:
     * <pre>{@code
     *     return 8_000;
     * }</pre>
     * 
     * This corresponds to <a
     * href="https://tools.ietf.org/html/rfc7230#section-3.1.1">section 3.1.1 in
     * RFC 7230</a> as well as <a
     * href="https://stackoverflow.com/a/8623061/1268003">common practice</a>.
     * 
     * @return number of request head bytes processed before exception is thrown
     */
    default int maxRequestHeadSize() {
        return 8_000;
    }
    
    /**
     * Returns the max number of attempts at recovering a failed request.<p>
     * 
     * This configuration has an effect only if the application has provided one
     * or more exception handlers to the server and is then the max number of
     * times the server is willing to attempt request recovery before giving
     * up.<p>
     * 
     * When all tries have been exhausted, the {@link ExceptionHandler#DEFAULT
     * default exception handler} will be called with the original exception.<p>
     * 
     * Successfully invoking an exception handler (handler returns a response or
     * throws a <i>different</i> exception instance) counts as one attempt.<p>
     * 
     * @implSpec
     * The default implementation returns {@code 5}.
     * 
     * @return max number of attempts
     * 
     * @see ExceptionHandler
     */
    default int maxErrorRecoveryAttempts() {
        return 5;
    }
}
