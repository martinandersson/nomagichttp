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
import alpha.nomagichttp.util.ByteBufferIterables;

import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/// Server configuration.
/// 
/// [Config#toBuilder()] allows for any configuration object to be used as a
/// template for a new instance.
/// 
/// The static method [#configuration()] is a shortcut for 
/// `Config.`[#DEFAULT]`.toBuilder()`:
/// 
/// {@snippet :
///    // @link substring="minHttpVersion" target="#minHttpVersion()" region
///    // @link substring="HTTP_1_1" target="HttpConstants.Version#HTTP_1_1" region
///    // @link substring="build" target="Builder#build()" region
///    new HttpServer(configuration()
///            .minHttpVersion(HTTP_1_1)
///            ...
///            .build());
///    // @end
///    // @end
///    // @end
///  }
/// 
/// @implSpec
/// The implementation is immutable.
/// 
/// The implementation inherits the identity-based implementations of
/// [Object#hashCode()] and [Object#equals(Object)].
/// 
/// @author Martin Andersson (webmaster at martinandersson.com)
public interface Config
{
    /// The configuration used by [HttpServer].
    /// 
    /// This instance contains the following values:
    /// 
    /// Max request head size = 8 000  
    /// Max request body buffer size = 20 971 520 (20 MB)  
    /// Max request trailers' size = 8 000  
    /// Max error responses = 3  
    /// Min HTTP version = 1.0  
    /// Discard rejected informational = true  
    /// Immediately continue Expect 100 = false  
    /// Timeout file lock = 3 seconds  
    /// Timeout idle connection = 3 minutes  
    /// Implement missing options = true
    Config DEFAULT = DefaultConfig.DefaultBuilder.ROOT.build();
    
    /// {@return the max number of request head bytes to process}
    /// 
    /// When the limit has been exceeded, a [MaxRequestHeadSizeException] is
    /// thrown.
    /// 
    /// The [#DEFAULT] configuration returns 8 000, which is the minimum
    /// recommended size
    /// ([RFC 7230 §3.1.1](https://tools.ietf.org/html/rfc7230#section-3.1.1))
    /// and also common amongst servers
    /// ([StackOverflow.com](https://stackoverflow.com/a/8623061/1268003)).
    int maxRequestHeadSize();
    
    /// {@return the max number of request body bytes to internally buffer}
    /// 
    /// Before (if an unacceptable length is known in advance) or when the limit
    /// has been exceeded, a [MaxRequestBodyBufferSizeException] is thrown.
    /// 
    /// The [#DEFAULT] implementation returns 20 971 520 (20 MB).
    /// 
    /// This configuration applies to high-level methods that internally
    /// buffer the entire body on Java's heap space. For example,
    /// [Request.Body#bytes()] and [Request.Body#toText()].
    /// 
    /// Otherwise, the request body has no size limit (nor is there such a
    /// configuration option). The application can consume an unlimited number
    /// of bytes by iterating the body, or using another method which does not
    /// buffer the body. For example,
    /// [`Request.Body.toFile(...)`][Request.Body#toFile(Path, long, TimeUnit, Set, FileAttribute\[\])]
    /// 
    /// @apiNote
    /// Without this configuration in place, it would have been too easy for a
    /// bad actor to crash the server (by streaming a body straight into memory
    /// until memory runs out).
    int maxRequestBodyBufferSize();
    
    /**
     * {@return the max number of request trailer bytes to process}<p>
     * 
     * Once the limit has been exceeded, a
     * {@link MaxRequestTrailersSizeException} is thrown.<p>
     * 
     * The default implementation returns {@code 8_000}.
     */
    int maxRequestTrailersSize();
    
    /**
     * {@return the max number of consecutively unsuccessful responses}<p>
     * 
     * An unsuccessful response is one with a status code classified as 4XX
     * (Client Error) or 5XX (Server Error).<p>
     * 
     * Once the limit has been reached, the client channel is closed.<p>
     * 
     * Closing the channel after repeatedly unsuccessful exchanges increases
     * security.<p>
     * 
     * The default implementation returns {@code 3}.
     */
    int maxErrorResponses();
    
    /**
     * {@return the minimum supported HTTP version}<p>
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
     */
    Version minHttpVersion();
    
    /**
     * {@return whether to discard 1XX (Informational) responses for
     * HTTP/1.0 clients}<p>
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
     */
    boolean discardRejectedInformational();
    
    /**
     * {@return whether to immediately reply a 100 (Continue) response}<p>
     * 
     * If {@code true} and the server receives a request with the
     * {@code Expect: 100-continue} header set, an 100 (Continue) interim
     * response will immediately be sent back, which prompts the client to
     * continue sending the rest of the request.<p>
     * 
     * This is how most servers work, which is dubious, as it effectively kills
     * the entire concept of having a client verify with the server first if it
     * is ready to receive a large body. Probably because most servers don't
     * support the hosted software to send interim responses to begin with
     * (very, very sad).<p>
     * 
     * The NoMagicHTTP server does support interim responses, and the default
     * value for this configuration is {@code false}, leaving it up to the
     * application to decide.<p>
     * 
     * That decision will either be an explicitly sent 100 (Continue) response,
     * or one implicitly sent upon the first access of a non-empty request body
     * (all methods in {@link Request.Body} except
     * {@link Request.Body#length() size} and
     * {@link Request.Body#isEmpty() isEmpty}).<p>
     * 
     * This means that the application developer does not need to be aware of
     * the {@code Expect: 100-continue} feature but will still receive the full
     * benefit, and the developer who is aware can take control as he
     * pleases.<p>
     * 
     * Regardless of the configured value, the server never attempts to
     * send a 100 (Continue) response to an HTTP/1.0 client since HTTP/1.0 does
     * not support interim responses (
     * <a href="https://tools.ietf.org/html/rfc7231#section-5.1.1">RFC 7231 §5.1.1</a>
     * 
     * @see HttpConstants.StatusCode#ONE_HUNDRED
     */
    boolean immediatelyContinueExpect100();
    
    /**
     * {@return max duration the library awaits a file lock}<p>
     * 
     * This configuration value is used by
     * {@link Request.Body#toFile(Path, OpenOption...)} and
     * {@link ByteBufferIterables#ofFile(Path)}.<p>
     * 
     * The call-site is free to cap the total length of the duration to
     * {@code Long.MAX_VALUE} nanoseconds (that's over 292 years).<p>
     * 
     * The default is 3 seconds.
     */
    Duration timeoutFileLock();
    
    /**
     * {@return the max duration a connection is allowed to be idle}<p>
     * 
     * The default is default is 3 minutes.<p>
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
     */
    // TODO: After HTTP/2 support, we'll have to cap the buffer anyways, so pro-tip in Javadoc can be removed
    //       (but instead the pro-tip will go somewhere else; please use a buffer no larger than max frame)
    Duration timeoutIdleConnection();
    
    /**
     * {@return whether to implement an {@value HttpConstants.Method#OPTIONS}
     * handler if not provided by the route}.<p>
     * 
     * The default is {@code true}, which works in the following way: As with
     * any HTTP method, if the route exists but the method implementation is
     * missing, a {@link MethodNotAllowedException} is thrown, which may
     * eventually reach the
     * {@linkplain ExceptionHandler#BASE base exception handler}. This handler
     * will then return to the server a <i>successful</i> 204 (No Content).<p>
     * 
     * Had this configuration not been enabled, the returned response would have
     * been a <i>client error</i> 405 (Method Not Allowed).<p>
     * 
     * In both cases, the {@value HttpConstants.HeaderName#ALLOW} header will be
     * set and populated with all the HTTP methods that are implemented. So
     * there's really no other difference between the two outcomes, other than
     * the status code.<p>
     * 
     * With or without this configuration enabled, the application is free to
     * add its own {@code OPTIONS} implementation to the route, or override the
     * base exception handler.
     */
    boolean implementMissingOptions();
    
     /**
     * {@return the builder instance that built this configuration object}<p>
     * 
     * The builder may be used to modify configuration values.
     */
    Config.Builder toBuilder();
    
    /**
     * {@return the builder used to build the default configuration}
     * 
     * The builder may be used to modify default configuration values.
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
         * Creates a new {@code Config} from the current state of this builder.
         * 
         * @return a new {@code Config}
         */
        Config build();
    }
}