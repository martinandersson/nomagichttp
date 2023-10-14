package alpha.nomagichttp;

import alpha.nomagichttp.HttpConstants.Version;
import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ExceptionHandler;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.MaxRequestBodyBufferSizeException;
import alpha.nomagichttp.message.MaxRequestHeadSizeException;
import alpha.nomagichttp.message.MaxRequestTrailersSizeException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.route.MethodNotAllowedException;
import alpha.nomagichttp.route.Route;
import alpha.nomagichttp.util.ByteBufferIterables;

import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Server configuration.<p>
 * 
 * The implementation is immutable and thread-safe.<p>
 * 
 * The configuration instance used by the server — if none is specified — is
 * {@link #DEFAULT}.<p>
 * 
 * Any configuration object can be turned into a builder for customization. The
 * static method {@link #configuration()} is a shortcut for {@code
 * Config.DEFAULT.toBuilder()}, and allows for fluent overrides of individual
 * values.<p>
 * 
 * {@snippet :
 *   // @link substring="minHttpVersion" target="#minHttpVersion()" region
 *   // @link substring="HTTP_1_1" target="HttpConstants.Version#HTTP_1_1" region
 *   new HttpServer(configuration()
 *           .minHttpVersion(HTTP_1_1)
 *           ...
 *           .build());
 *   // @end
 *   // @end
 * }
 * 
 * In the JDK Reference Implementation, the number of platform threads available
 * for scheduling virtual threads may be specified using the system property
 * <pre>jdk.virtualThreadScheduler.parallelism</pre> (see JavaDoc of
 * {@link Thread}).
 * 
 * @author Martin Andersson (webmaster at martinandersson.com
 */
public interface Config
{
    /**
     * This configuration instance contains the following values:<p>
     * 
     * Max request head size = 8 000 <br>
     * Max request body buffer size = 20 971 520 (20 MB) <br>
     * Max request trailers' size = 8 000 <br>
     * Max error responses = 3 <br>
     * Min HTTP version = 1.0 <br>
     * Discard rejected informational = true <br>
     * Immediately continue Expect 100 = false <br>
     * Timeout file lock = 3 seconds <br>
     * Timeout idle connection = 3 minutes <br>
     * Implement missing options = true
     */
    Config DEFAULT = DefaultConfig.DefaultBuilder.ROOT.build();
    
    /**
     * Returns the max number of bytes processed while parsing a request head
     * before giving up.<p>
     * 
     * Once the limit has been exceeded, a {@link MaxRequestHeadSizeException}
     * is thrown.<p>
     * 
     * The default implementation returns {@code 8_000}.<p>
     * 
     * The default value corresponds to <a
     * href="https://tools.ietf.org/html/rfc7230#section-3.1.1">RFC 7230 §3.1.1</a>
     * as well as
     * <a href="https://stackoverflow.com/a/8623061/1268003">common practice</a>.
     * 
     * @return number of request head bytes processed before exception
     */
    int maxRequestHeadSize();
    
    /**
     * Returns the max number of bytes that the {@link Request.Body} API accepts
     * to internally buffer.<p>
     * 
     * Once the limit has been exceeded, a
     * {@link MaxRequestBodyBufferSizeException} is thrown.<p>
     * 
     * This configuration applies <i>only</i> to high-level methods that
     * internally buffer the whole body, such as {@link Request.Body#bytes()}
     * and {@link Request.Body#toText()}.<p>
     * 
     * The request body size itself has no limit (nor is there such a
     * configuration option). The application can consume an unlimited number
     * of bytes however it desires, by iterating the body, or use other methods
     * that do not buffer the body, such as
     * {@link Request.Body#toFile(Path, long, TimeUnit, Set, FileAttribute[]) Request.Body.toFile(Path, ...)}
     * <p>
     * 
     * Methods provided by the library that collect bytes in the memory, must be
     * constrained for a number of a reasons. Perhaps primarily because it would
     * have been too easy for a bad actor to crash the server (by streaming a
     * body straight into memory until memory runs out).<p>
     * 
     * The default implementation returns {@code 20_971_520} (20 MB).
     * 
     * @return see JavaDoc
     */
    int maxRequestBodyBufferSize();
    
    /**
     * Returns the max number of bytes processed while parsing request trailers
     * before giving up.<p>
     * 
     * Once the limit has been exceeded, a
     * {@link MaxRequestTrailersSizeException} is thrown.<p>
     * 
     * The default implementation returns {@code 8_000}.
     * 
     * @return number of request trailer bytes processed before exception
     */
    int maxRequestTrailersSize();
    
    /**
     * Returns the max number of consecutive responses sent to a client of
     * classification 4XX (Client Error) and 5XX (Server Error) before closing
     * the client channel.<p>
     * 
     * Closing the channel after repeatedly unsuccessful exchanges increases
     * security.<p>
     * 
     * The default implementation returns {@code 3}.
     * 
     * @return max number of consecutively unsuccessful responses
     *         before closing channel
     */
    int maxErrorResponses();
    
    /**
     * Returns the minimum supported HTTP version.<p>
     * 
     * By default, this method returns
     * {@link HttpConstants.Version#HTTP_1_0 HTTP/1.0}.<p>
     * 
     * If a client sends a request with an older HTTP version than what is
     * configured, an {@link HttpVersionTooOldException} is thrown, which by
     * default gets translated to a 426 (Upgrade Required) response.<p>
     * 
     * HTTP/1.0 does not support persistent connections and there are a number
     * of issues related to the unofficial "keep-alive" mechanism — which the
     * NoMagicHTTP server does not implement. All HTTP/1.0 connections will
     * therefore close after each response, which is inefficient. It's
     * recommended to set this value to
     * {@link HttpConstants.Version#HTTP_1_1 HTTP/1.1}. As a library however, we
     * have to be backwards compatible and support as many applications as
     * possible "out of the box".<p>
     * 
     * The minimum version the NoMagicHTTP implements is HTTP/1.0, and the
     * maximum version currently implemented is HTTP/1.1.
     * 
     * @return the minimum supported HTTP version
     */
    Version minHttpVersion();
    
    /**
     * Discard 1XX (Informational) responses when the recipient is a client
     * using an HTTP version older than HTTP/1.1.<p>
     * 
     * The default value is {@code true} and the application can safely write
     * 1XX responses to the channel without a concern for incompatible
     * clients.<p>
     * 
     * Turning this option off causes {@link ClientChannel#write(Response)} to
     * throw a {@link ResponseRejectedException} for 1XX responses when the
     * client is incompatible. This necessitates that the application must
     * either handle the exception explicitly, or query the active HTTP version
     * ({@link Request#httpVersion()}) before attempting to send such a
     * response.
     * 
     * @return whether to discard 1XX responses for incompatible clients
     */
    boolean discardRejectedInformational();
    
    /**
     * Immediately respond a 100 (Continue) interim response to a request with a
     * {@code Expect: 100-continue} header.<p>
     * 
     * This value is by default {@code false}, which enables the application to
     * delay the client's body submission until it responds a 100 (Continue)
     * response.<p>
     * 
     * Even when this configuration value is {@code false}, the server will
     * still attempt to respond a 100 (Continue) response to the client, but
     * delayed until the application's first access of a non-empty request body
     * (all methods in {@link Request.Body} except
     * {@link Request.Body#length() size} and
     * {@link Request.Body#isEmpty() isEmpty}).<p>
     * 
     * This means that the application developer does not need to be aware of
     * the {@code Expect: 100-continue} feature but still receive the full
     * benefit, and the developer who is aware can take control as he
     * pleases.<p>
     * 
     * Regardless of the configured value, the server never attempts to
     * send a 100 (Continue) response to an HTTP/1.0 client since HTTP/1.0 does
     * not support interim responses (
     * <a href="https://tools.ietf.org/html/rfc7231#section-5.1.1">RFC 7231 §5.1.1</a>
     * ).
     * 
     * @return whether to immediately respond a 100 (Continue) interim response
     *         to a request with a {@code Expect: 100-continue} header
     * 
     * @see HttpConstants.StatusCode#ONE_HUNDRED
     */
    boolean immediatelyContinueExpect100();
    
    /**
     * Max duration the library awaits a file lock.<p>
     * 
     * This configuration value is used by
     * {@link Request.Body#toFile(Path, OpenOption...)} and
     * {@link ByteBufferIterables#ofFile(Path)}.<p>
     * 
     * The call-site is free to cap the total length of the duration to
     * {@code Long.MAX_VALUE} nanoseconds (that's over 292 years).
     * 
     * @return file-lock timeout duration (default is 3 seconds)
     */
    Duration timeoutFileLock();
    
    /**
     * Max duration a connection is allowed to be idle.<p>
     * 
     * The timer leading up to the timeout is <i>started</i> each time a client
     * channel operation begins (reading or writing), and <i>stops</i> when the
     * operation completes. On timeout, the operation returns exceptionally
     * with an {@link IdleConnectionException}.<p>
     * 
     * Outside of request processing logic and response writing, the server will
     * have an outstanding read operation awaiting the client's request, which
     * correctly triggers the timeout if the client idles.<p>
     * 
     * Request processing logic can take however long it wants to; there is no
     * active channel operation, and so forever-stalling application code will
     * never cause a timeout. There's simply no way — disregarding interrupts —
     * that the server can intercept arbitrary work of the application code.
     * Besides, when the read and write operations commence (application code
     * has yielded control), well then the request thread was no longer
     * stalling, so from the server's point of view, not a problem.<p>
     * 
     * The timer is never reset <i>during</i> the channel operation. The
     * application should therefore not allocate and use unimaginably large
     * bytebuffers for response bodies, as this could theoretically cause a
     * timeout to happen if writing such a bytebuffer takes longer than the
     * configured timeout value, despite that the transfer may actually still be
     * making progress.<p>
     * 
     * The minimum acceptable outbound transfer rate can be calculated: Suppose
     * the server is writing a response body which yields bytebuffers with a
     * size of 16 384 bytes (16 * 1024 bytes, the max size of an HTTP/2 frame),
     * then writing has to be, on average, slower than 0.09102 kB/s (kilobytes
     * per second), or 0.00009102 MB/s (megabytes per second) during three
     * minutes (the default timeout) for the timeout to happen.<p>
     * 
     * This calculated minimum rate is well within the transfer rate of cellular
     * networks even older than 2G (10-15% of Cellular Digital Packet Data
     * rate).<p>
     * 
     * The server's read-buffer is 512 bytes large, so will accept an even
     * slower inbound transfer rate.<p>
     * 
     * There is currently no configuration to explicitly set minimum transfer
     * rates.
     * 
     * @return idle connection timeout duration (default is 3 minutes)
     */
    // TODO: After HTTP/2 support, we'll have to cap the buffer anyways, so pro-tip in Javadoc can be removed
    //       (but instead the pro-tip will go somewhere else; please use a buffer no larger than max frame)
    Duration timeoutIdleConnection();
    
    /**
     * If {@code true} (which is the default), any {@link Route} not
     * implementing the {@value HttpConstants.Method#OPTIONS} method will get
     * one.<p>
     * 
     * This works in the following way: As with any HTTP method, if the route
     * exists but the method implementation is missing, a {@link
     * MethodNotAllowedException} is thrown which may eventually reach the
     * {@linkplain ExceptionHandler#BASE base exception handler}. This handler
     * in turn will - if this configuration is enabled - respond a
     * <i>successful</i> 204 (No Content). Had this configuration not been
     * enabled, the response would have been a <i>client error</i> 405
     * (Method Not Allowed).<p>
     * 
     * In both cases, the {@value HttpConstants.HeaderName#ALLOW} header will be
     * set and populated with all the HTTP methods that are implemented. So
     * there's really no other difference between the two outcomes, other than
     * the status code.<p>
     * 
     * With or without this configuration enabled, the application can easily
     * add its own {@code OPTIONS} implementation to the route or override the
     * base exception handler.
     * 
     * @return see JavaDoc
     */
    boolean implementMissingOptions();
    
     /**
     * Returns the builder instance that built this configuration.<p>
     * 
     * The builder may be used to modify configuration values.
     * 
     * @return the builder instance that built this configuration object
     */
    Config.Builder toBuilder();
    
    /**
     * Returns the builder used to build the default configuration.
     * 
     * @return the builder used to build the default configuration
     * @see #toBuilder()
     */
    static Config.Builder configuration() {
        return DEFAULT.toBuilder();
    }
    
    /**
     * Builder of a {@link Config}.<p>
     * 
     * The builder can be used as a template to modify configuration state. Each
     * method returns a new builder instance representing the new state. The API
     * should be used in a fluent style. There's generally no reason to save a
     * builder reference as the builder that built a configuration object can be
     * retrieved using {@link Config#toBuilder()}<p>
     * 
     * The implementation is thread-safe.<p>
     * 
     * The implementation does not necessarily implement {@code hashCode()} and
     * {@code equals()}.
     * 
     * @author Martin Andersson (webmaster at martinandersson.com)
     */
    interface Builder {
        /**
         * Sets a new value.<p>
         * 
         * The value can be any integer, although a value too small (or
         * negative) will risk rejecting all requests.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#maxRequestHeadSize()
         */
        Builder maxRequestHeadSize(int newVal);
        
        /**
         * Sets a new value.<p>
         * 
         * The value can be any integer, although a value too small (or
         * negative) will risk failing most request body conversions.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#maxRequestBodyBufferSize()
         */
        Builder maxRequestBodyBufferSize(int newVal);
        
        /**
         * Sets a new value.<p>
         * 
         * The value can be any integer, although a value too small (or
         * negative) will risk closing the connection early.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#maxErrorResponses()
         */
        Builder maxErrorResponses(int newVal);
        
        /**
         * Sets a new value.
         * 
         * @param newVal new value
         * 
         * @return a new builder representing the new state
         * 
         * @throws NullPointerException
         *             if {@code newVal} is {@code null}
         * 
         * @throws IllegalArgumentException
         *             if {@code newVal} is less than HTTP/1.0
         *             or greater than HTTP/1.1
         * 
         * @see Config#minHttpVersion()
         */
        Builder minHttpVersion(Version newVal);
        
        /**
         * Sets a new value.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#discardRejectedInformational()
         */
        Builder discardRejectedInformational(boolean newVal);
        
        /**
         * Sets a new value.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#immediatelyContinueExpect100()
         */
        Builder immediatelyContinueExpect100(boolean newVal);
        
        /**
         * Sets a new value.<p>
         * 
         * The value can be any duration, and even empty, which would
         * effectively make the library API not spend any time waiting on a
         * lock. What happens if the duration is negative is not specified.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @throws NullPointerException if {@code newVal} is {@code null}
         * @see Config#timeoutFileLock()
         */
        Builder timeoutFileLock(Duration newVal);
        
        /**
         * Sets a new value.<p>
         * 
         * The value can be any duration and should realistically represent a
         * significant chunk of time. What happens if the duration is negative
         * is not specified.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @throws NullPointerException if {@code newVal} is {@code null}
         * @see Config#timeoutIdleConnection()
         */
        Builder timeoutIdleConnection(Duration newVal);
        
        /**
         * Sets a new value.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#implementMissingOptions()
         */
        Builder implementMissingOptions(boolean newVal);
        
        /**
         * Builds a configuration object.
         * 
         * @return the configuration object
         */
        Config build();
    }
}