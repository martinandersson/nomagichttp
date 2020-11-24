package alpha.nomagichttp;

import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;

/**
 * Server configuration.<p>
 * 
 * The implementation must be thread-safe.
 * 
 * @author Martin Andersson (webmaster at martinandersson.com
 */
public interface ServerConfig {
    /**
     * Values used:<p>
     * 
     * Max request head size = 8 000 <br>
     * Max error recovery attempts = 5 <br>
     * Thread pool-size = {@code Runtime.getRuntime().availableProcessors()}
     */
    ServerConfig DEFAULT = new ServerConfig(){};
    
    /**
     * Returns the max number of bytes processed while parsing a request
     * head before giving up.<p>
     * 
     * Once the limit has been exceeded, a {@link
     * MaxRequestHeadSizeExceededException} will be thrown.<p>
     * 
     * This configuration value will be polled at the start of each new request.
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
     * or more error handlers to the server.<p>
     * 
     * When all tries have been exhausted, the {@link ErrorHandler#DEFAULT
     * default error handler} will be called with the original exception.<p>
     * 
     * Successfully invoking an error handler (handler returns a response or
     * throws a <i>different</i> exception instance) counts as one attempt.<p>
     * 
     * This configuration value will be polled at the start of each recovery
     * attempt.
     * 
     * @implSpec
     * The default implementation returns {@code 5}.
     * 
     * @return max number of attempts
     * 
     * @see ErrorHandler
     */
    default int maxErrorRecoveryAttempts() {
        return 5;
    }
    
    /**
     * Returns the number of request threads that should be allocated for
     * executing HTTP exchanges (such as calling the application-provided
     * request- and error handlers).<p>
     * 
     * For a runtime change of this value to have an effect, all server
     * instances must restart.
     * 
     * @implSpec
     * The default implementation returns {@link Runtime#availableProcessors()}.
     * 
     * @return thread pool size
     */
    default int threadPoolSize() {
        return Runtime.getRuntime().availableProcessors();
    }
}
