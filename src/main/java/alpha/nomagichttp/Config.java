package alpha.nomagichttp;

import alpha.nomagichttp.handler.ClientChannel;
import alpha.nomagichttp.handler.ErrorHandler;
import alpha.nomagichttp.handler.ResponseRejectedException;
import alpha.nomagichttp.message.HttpVersionTooOldException;
import alpha.nomagichttp.message.MaxRequestBodyConversionSizeExceededException;
import alpha.nomagichttp.message.MaxRequestHeadSizeExceededException;
import alpha.nomagichttp.message.MaxRequestTrailersSizeExceededException;
import alpha.nomagichttp.message.ReadTimeoutException;
import alpha.nomagichttp.message.Request;
import alpha.nomagichttp.message.Response;
import alpha.nomagichttp.message.ResponseTimeoutException;
import alpha.nomagichttp.message.Responses;
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
 * The implementation used if none is specified is {@link #DEFAULT}.<p>
 * 
 * Any configuration object can be turned into a builder for customization. The
 * static method {@link #configuration()} is a shortcut for {@code
 * Config.DEFAULT.toBuilder()}.<p>
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
     * Values used:<p>
     * 
     * Max request head size = 8 000 <br>
     * Max unsuccessful responses = 3 <br>
     * Reject clients using HTTP/1.0 = true <br>
     * Ignore rejected informational = true <br>
     * Immediately continue Expect 100 = false <br>
     * Timeout idle connection = 90 seconds
     */
    Config DEFAULT = DefaultConfig.DefaultBuilder.ROOT.build();
    
    /**
     * Returns the max number of bytes processed while parsing a request head
     * before giving up.<p>
     * 
     * Once the limit has been exceeded, a {@link
     * MaxRequestHeadSizeExceededException} is thrown.<p>
     * 
     * The default implementation returns {@code 8_000}.
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
     * Once the limit has been exceeded, a {@link
     * MaxRequestBodyConversionSizeExceededException} is thrown.<p>
     * 
     * This configuration applies <i>only</i> to high-level methods that
     * internally buffer up the whole body, such as {@link Request.Body#bytes()}
     * and {@link Request.Body#toText()}.<p>
     * 
     * The request body size itself has no limit (nor is there such a
     * configuration option). The application can consume an unlimited amount
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
    int maxRequestBodyConversionSize();
    
    /**
     * Returns the max number of bytes processed while parsing request trailers
     * before giving up.<p>
     * 
     * Once the limit has been exceeded, a {@link
     * MaxRequestTrailersSizeExceededException} is thrown.<p>
     * 
     * The default implementation returns {@code 8_000}.
     * 
     * @return number of request trailer bytes processed before exception
     */
    // TODO: Rename to length
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
    int maxUnsuccessfulResponses();
    
    /**
     * Reject HTTP/1.0 clients, yes or no.<p>
     * 
     * By default, this method returns {@code false} and the server will
     * therefore accept HTTP/1.0 clients.<p>
     * 
     * Rejection takes place through a server-thrown {@link
     * HttpVersionTooOldException} which by default gets translated to a
     * "426 Upgrade Required" response.<p>
     * 
     * HTTP/1.0 does not by default support persistent connections and there
     * are a number of issues related to HTTP/1.0's Keep-Alive mechanism which
     * the NoMagicHTTP server does not support. All HTTP/1.0 connections will
     * therefore close after each response, which is inefficient. It's
     * recommended to override this value with {@code true}. As a library
     * however, we have to be backwards compatible and support as many
     * applications as possible "out of the box", hence the {@code false}
     * default.<p>
     * 
     * Note that HTTP/0.9 or older clients are always rejected (can not be
     * configured differently).<p>
     * 
     * The default implementation returns {@code false}.
     * 
     * @return whether to reject HTTP/1.0 clients
     */
    boolean rejectClientsUsingHTTP1_0();
    
    /**
     * Discard 1XX (Informational) responses when the recipient is an
     * HTTP/1.0 client.<p>
     * 
     * The default value is {@code true} and the application can safely write
     * 1XX responses to the channel without concern for incompatible clients.<p>
     * 
     * Turning this option off causes {@link ClientChannel#write(Response)} to
     * throw a {@link ResponseRejectedException} if the response is 1XX and the
     * recipient is incompatible. This necessitates that the application must
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
     * delay the client's body submission until it responds a 100 (Continue).<p>
     * 
     * Even when this configuration value is {@code false}, the server will
     * still attempt to respond a 100 (Continue) to the client, but delayed
     * until the application's first access of a non-empty request body (all
     * methods in {@link Request.Body} except {@link Request.Body#length() size}
     * and {@link Request.Body#isEmpty() isEmpty}).<p>
     * 
     * This means that the application developer does not need to be aware of
     * the {@code Expect: 100-continue} but still receive the full benefit, and
     * the developer who is aware can take full charge as he pleases.<p>
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
     * Max duration allowed for a channel read operation to complete.<p>
     * 
     * On timeout, the underlying read-stream will shut down and then a {@link
     * ReadTimeoutException} is thrown.<p>
     * 
     * If the timeout occurs while a request head is expected or in-flight, then
     * it'll be delivered to the error handler(s), the {@linkplain
     * ErrorHandler#BASE default} of which translates the exception to a
     * {@linkplain Responses#requestTimeout() 408 (Request Timeout)}.<p>
     * 
     * If the timeout occurs while there is an active request body subscriber,
     * it'll be delivered to the subscriber's {@code onError} method. If the
     * subscriber is the server's own discarding body subscriber, then the error
     * will be delivered to the error handler(s). An application-installed body
     * subscriber must deal with the timeout exception just as it needs to be
     * prepared to deal with any other error passed to the subscriber. Failure
     * to handle the exception will eventually result in a {@code
     * ResponseTimeoutException} instead.<p>
     * 
     * The timer is only reset once a read-buffer has filled up and a new read
     * operation is initiated. Therefore, an extremely slow client may cause a
     * read timeout even though the connection is technically still making
     * progress. Similarly, the timeout may also happen because the
     * application's body subscriber takes too long processing a bytebuffer. It
     * can be argued that the purpose of the timeout is not so much to protect
     * against a stale connection but rather to protect against HTTP exchanges
     * not making progress, whoever is at fault.<p>
     * 
     * The minimum acceptable transfer rate can be calculated. The default
     * timeout duration is one and a half minute and the default server's buffer
     * size is 16 384 bytes. This works out to a possible (dependent on request
     * size, and so on) timeout for clients sending data on average equal to or
     * slower than 1.456 kb/s (0.001456 Mbit/s) for one and a half minute. This
     * calculated minimum rate is well within the transfer rate of cellular
     * networks even older than 2G (10-15% of Cellular Digital Packet Data
     * rate). But if the application's clients are expected to be on Mars, then
     * perhaps the timeout ought to be increased.<p>
     * 
     * If the application does not expect more requests and wishes to maintain
     * an outbound connection with the client used for interim responses
     * stretched over a long time or any other type of uni-directional
     * streaming, simply call {@link ClientChannel#shutdownInput()} which
     * effectively stops the timer.
     * 
     * @return read timeout duration (default is one and a half minute)
     * @see #timeoutResponse()
     */
    Duration timeoutRead();
    
    /**
     * Max duration allowed to await a response and response trailers.<p>
     * 
     * Assuming that the write stream is still open when the timeout occurs, a
     * {@link ResponseTimeoutException} will be delivered to the error
     * handler(s), the {@linkplain ErrorHandler#BASE default} of which
     * translates it to a {@linkplain Responses#serviceUnavailable() 503
     * (Service Unavailable)}.<p>
     * 
     * The timer is active while the client channel is expecting to receive a
     * response and also while the server's response body subscriber has
     * unfulfilled outstanding demand. The first time the timer is activated is
     * when the request invocation chain completes and the last request body
     * bytebuffer has been released, and so the response will never time out
     * while a request is still actively being received or processed.<p>
     * 
     * A response producer that needs more time can reset the timer by sending a
     * 1XX (Informational) interim response or any other type of heartbeat.<p>
     * 
     * The timer is also active after the response body publisher has completed
     * until {@link Response#trailers()}, if provided, completes.<p>
     * 
     * The timer is not active while a response is being transmitted on the
     * wire, this is covered by {@link #timeoutWrite()}.
     * 
     * @return response timeout duration (default is one and a half minute)
     */
    Duration timeoutResponse();
    
    /**
     * Max duration allowed for a channel write operation to complete.<p>
     * 
     * Analogous to the built-in protection against slow clients when receiving
     * data, this timer will cause the underlying channel write operation to
     * abort for response body bytebuffers not fully sent before the duration
     * elapses. The exception will not be delivered to the error handler(s) and
     * instead cause the channel to close immediately.<p>
     * 
     * The application can choose to publish very large response body
     * bytebuffers without worrying about a possible timeout due to the
     * increased time it may take to send a large buffer. The server will
     * internally slice the buffer [if need be] to match the read-operation's
     * buffer size.
     * 
     * @return write timeout duration (default is one and a half minute)
     * @see #timeoutRead()
     */
    Duration timeoutWrite();
    
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
     * If {@code true} (which is the default), any {@link Route} not
     * implementing the {@value HttpConstants.Method#OPTIONS} method will get
     * one.<p>
     * 
     * This works in the following way: As with any HTTP method, if the route
     * exists but the method implementation is missing, a {@link
     * MethodNotAllowedException} is thrown which may eventually reach the
     * {@linkplain ErrorHandler#BASE base error handler}. This handler in turn
     * will - if this configuration is enabled - respond a <i>successful</i> 204
     * (No Content). Had this configuration not been enabled, the response would
     * have been a <i>client error</i> 405 (Method Not Allowed). In both cases,
     * the {@value HttpConstants.HeaderName#ALLOW} header will be set and
     * populated with all the HTTP methods that are implemented. So there's
     * really no other difference between the two outcomes, other than the
     * status code.<p>
     * 
     * With or without this configuration enabled, the application can easily
     * add its own {@code OPTIONS} implementation to the route or override the
     * base error handler.
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
         * @see Config#maxRequestBodyConversionSize()
         */
        Builder maxRequestBodyConversionSize(int newVal);
        
        /**
         * Sets a new value.<p>
         * 
         * The value can be any integer, although a value too small (or
         * negative) will risk closing the connection early.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#maxUnsuccessfulResponses()
         */
        Builder maxUnsuccessfulResponses(int newVal);
        
        /**
         * Sets a new value.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#rejectClientsUsingHTTP1_0()
         */
        Builder rejectClientsUsingHTTP1_0(boolean newVal);
        
        /**
         * Sets a new value.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @see Config#discardRejectedInformational()
         */
        Builder ignoreRejectedInformational(boolean newVal);
        
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
         * The value can be any duration, although a too short (or negative)
         * duration will effectively make the server useless.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @throws NullPointerException if {@code newVal} is {@code null}
         * @see Config#timeoutRead()
         */
        Builder timeoutRead(Duration newVal);
        
        /**
         * Sets a new value.<p>
         * 
         * The value can be any duration, although a too short (or negative)
         * duration will effectively make the server useless.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @throws NullPointerException if {@code newVal} is {@code null}
         * @see Config#timeoutResponse()
         */
        Builder timeoutResponse(Duration newVal);
        
        /**
         * Sets a new value.<p>
         * 
         * The value can be any duration, although a too short (or negative)
         * duration will effectively make the server useless.
         * 
         * @param newVal new value
         * @return a new builder representing the new state
         * @throws NullPointerException if {@code newVal} is {@code null}
         * @see Config#timeoutWrite()
         */
        Builder timeoutWrite(Duration newVal);
        
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